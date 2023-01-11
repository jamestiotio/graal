/*
 * Copyright (c) 2015, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.identityhashcode;

import java.util.SplittableRandom;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.nodes.NamedLocationIdentity;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.replacements.IdentityHashCodeSnippets;
import org.graalvm.compiler.replacements.ReplacementsUtil;
import org.graalvm.compiler.word.ObjectAccess;
import org.graalvm.compiler.word.Word;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.SignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.core.threadlocal.FastThreadLocalObject;
import com.oracle.svm.core.util.VMError;

import jdk.internal.misc.Unsafe;

public final class IdentityHashCodeSupport {
    public static final LocationIdentity IDENTITY_HASHCODE_LOCATION = NamedLocationIdentity.mutable("identityHashCode");

    private static final FastThreadLocalObject<SplittableRandom> hashCodeGeneratorTL = FastThreadLocalFactory.createObject(SplittableRandom.class, "IdentityHashCodeSupport.hashCodeGeneratorTL");

    /**
     * Initialization can require synchronization which is not allowed during safepoints, so this
     * method can be called before using identity hash codes during a safepoint operation.
     */
    public static void ensureInitialized() {
        new SplittableRandom().nextInt();
    }

    public static IdentityHashCodeSnippets.Templates createSnippetTemplates(OptionValues options, Providers providers) {
        return SubstrateIdentityHashCodeSnippets.createTemplates(options, providers);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static int generateIdentityHashCode(Object obj) {
        int newHashCode;
        ObjectLayout ol = ConfigurationValues.getObjectLayout();
        if (ol.hasFixedIdentityHashField()) {
            newHashCode = computeRandomHashCode();

            if (!Unsafe.getUnsafe().compareAndSetInt(obj, ol.getFixedIdentityHashOffset(), 0, newHashCode)) {
                newHashCode = ObjectAccess.readInt(obj, ol.getFixedIdentityHashOffset(), IDENTITY_HASHCODE_LOCATION);
            }
        } else {
            newHashCode = assignHashCodeFromAddress(obj, 0);
            if (newHashCode == 0) {
                long newSalt;
                do {
                    newSalt = ensureRandomGenerator().nextLong();
                } while (newSalt == 0);
                newHashCode = assignHashCodeFromAddress(obj, newSalt);
            }
        }
        VMError.guarantee(newHashCode != 0, "Must not return 0 because it can mean 'hash code not computed yet'");
        assert newHashCode > 0 : "The Java HotSpot VM only returns positive numbers for the identity hash code, so we want to have the same restriction on Substrate VM in order to not surprise users";
        return newHashCode;
    }

    @Uninterruptible(reason = "Prevent a GC interfering with the object's identity hash state.")
    private static int assignHashCodeFromAddress(Object obj, long newSalt) {
        ObjectHeader oh = Heap.getHeap().getObjectHeader();
        if (oh.hasIdentityHashField(obj)) { // a GC has created the field since we last checked
            return ObjectAccess.readInt(obj, LayoutEncoding.getOptionalIdentityHashOffset(obj), IDENTITY_HASHCODE_LOCATION);
        } else {
            long salt = Heap.getHeap().getOrSetIdentityHashSalt(obj, newSalt);
            if (salt == 0) {
                assert newSalt == 0;
                return 0; // must call again with a salt computed outside of uninterruptible code
            }
            oh.setIdentityHashFromAddress(obj);
            return computeHashCodeFromAddress(obj, salt);
        }
    }

    @Uninterruptible(reason = "Prevent a GC interfering with the object's identity hash state.", callerMustBe = true)
    public static int computeHashCodeFromAddress(Object obj) {
        long salt = Heap.getHeap().getOrSetIdentityHashSalt(obj, 0);
        return computeHashCodeFromAddress(obj, salt);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int computeHashCodeFromAddress(Object obj, long salt) {
        if (GraalDirectives.inIntrinsic()) {
            ReplacementsUtil.dynamicAssert(salt != 0, "must have salt");
        } else {
            assert salt != 0;
        }
        Word address = Word.objectToUntrackedPointer(obj);
        SignedWord salted = WordFactory.signed(salt).xor(address);
        int hash = mix32(salted.rawValue()) >>> 1;
        return (hash == 0) ? 1 : hash;
    }

    /** Avalanching bit mixer, from {@link SplittableRandom}. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int mix32(long a) {
        long z = a;
        z = (z ^ (z >>> 33)) * 0x62a9d9ed799705f5L;
        return (int) (((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32);
    }

    private static SplittableRandom ensureRandomGenerator() {
        SplittableRandom hashCodeGenerator = hashCodeGeneratorTL.get();
        if (hashCodeGenerator == null) {
            /*
             * Create a new thread-local random number generator. SplittableRandom ensures that
             * values created by different random number generator instances are random as a whole.
             */
            hashCodeGenerator = new SplittableRandom();
            hashCodeGeneratorTL.set(hashCodeGenerator);
        }
        return hashCodeGenerator;
    }

    private static int computeRandomHashCode() {
        /*
         * The range of nextInt(MAX_INT) includes 0 and excludes MAX_INT, so adding 1 gives us the
         * range [1, MAX_INT] that we want.
         */
        return ensureRandomGenerator().nextInt(Integer.MAX_VALUE) + 1;
    }
}
