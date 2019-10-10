/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.dsl.processor.interop;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.TruffleTypes;
import com.oracle.truffle.dsl.processor.java.ElementUtils;

class KeysGenerator extends MessageGenerator {

    private static final String TARGETABLE_KEYS_NODE = "TargetableKeysNode";
    private int parameterCount = 1;

    KeysGenerator(ProcessingEnvironment processingEnv, AnnotationMirror resolveAnnotation, AnnotationMirror messageResolutionAnnotation,
                    TypeElement element,
                    ForeignAccessFactoryGenerator containingForeignAccessFactory) {
        super(processingEnv, resolveAnnotation, messageResolutionAnnotation, element, containingForeignAccessFactory);
    }

    @Override
    int getParameterCount() {
        return parameterCount;
    }

    @Override
    void appendRootNode(Writer w) throws IOException {
        w.append(indent).append("    private static final class ").append(rootNodeName).append(" extends RootNode {\n");
        w.append(indent).append("        protected ").append(rootNodeName).append("() {\n");
        w.append(indent).append("            super(null);\n");
        w.append(indent).append("        }\n");
        w.append("\n");
        w.append(indent).append("        @Child private ").append(clazzName).append(" node = ").append(getGeneratedDSLNodeQualifiedName()).append(".create();");
        w.append("\n");
        appendGetName(w);
        w.append(indent).append("        @Override\n");
        w.append(indent).append("        public Object execute(VirtualFrame frame) {\n");
        w.append(indent).append("            Object receiver = com.oracle.truffle.api.interop.ForeignAccess.getReceiver(frame);\n");
        if (parameterCount == 2) {
            w.append(indent).append("            Object[] arguments = frame.getArguments();\n");
            w.append(indent).append("            Object internal = (arguments.length < 2) ? false : arguments[1];\n");
        }
        w.append(indent).append("            try {\n");
        if (parameterCount == 2) {
            w.append(indent).append("                return node.executeWithTarget(frame, receiver, internal);\n");
        } else {
            w.append(indent).append("                return node.executeWithTarget(frame, receiver);\n");
        }
        w.append(indent).append("            } catch (UnsupportedSpecializationException e) {\n");
        appendHandleUnsupportedTypeException(w);
        w.append(indent).append("            }\n");
        w.append(indent).append("        }\n");
        w.append("\n");
        w.append(indent).append("    }\n");
    }

    @Override
    String getTargetableNodeName() {
        return TARGETABLE_KEYS_NODE;
    }

    @Override
    public String checkSignature(ExecutableElement method) {
        final List<? extends VariableElement> params = method.getParameters();
        boolean hasFrameArgument = false;
        boolean hasInternalArgument = false;
        if (params.size() >= 1) {
            TruffleTypes types = ProcessorContext.getInstance().getTypes();
            hasFrameArgument = ElementUtils.areTypesCompatible(params.get(0).asType(), types.VirtualFrame);
            int lastIndex = params.size() - 1;
            hasInternalArgument = ElementUtils.areTypesCompatible(params.get(lastIndex).asType(), processingEnv.getTypeUtils().getPrimitiveType(TypeKind.BOOLEAN));
        }

        int expectedNumberOfArguments = 1;
        if (hasFrameArgument) {
            expectedNumberOfArguments++;
        }
        if (hasInternalArgument) {
            expectedNumberOfArguments++;
        }

        if (params.size() != expectedNumberOfArguments) {
            return "Wrong number of arguments. Expected signature: ([frame: VirtualFrame], receiverObject: TruffleObject [, internal: boolean])";
        }

        parameterCount = (hasInternalArgument) ? 2 : 1;

        return super.checkSignature(method);
    }

}
