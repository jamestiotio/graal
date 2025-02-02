/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.core.test;

import java.util.List;

import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.junit.Test;

public class NodeCopyWithInputsTest extends GraalCompilerTest {

    static void snippet() {

    }

    @Test
    public void testDuplicateEquals() {
        StructuredGraph graph = parseEager("snippet", StructuredGraph.AllowAssumptions.YES);

        // Duplicating a FrameState should produce an equivalent node.
        List<FrameState> snapshot = graph.getNodes().filter(FrameState.class).snapshot();
        assertTrue(!snapshot.isEmpty(), "must produce some FrameStates");
        for (FrameState state : snapshot) {
            Node copy = state.copyWithInputs(true);
            assertTrue(copy.dataFlowEquals(state), "duplicated node should be equal");
        }
    }
}
