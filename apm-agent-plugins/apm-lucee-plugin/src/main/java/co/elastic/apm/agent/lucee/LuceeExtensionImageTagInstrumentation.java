/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2020 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.agent.lucee;

import co.elastic.apm.agent.bci.TracerAwareInstrumentation;
import co.elastic.apm.agent.bci.VisibleForAdvice;
import co.elastic.apm.agent.bci.HelperClassManager;
import co.elastic.apm.agent.http.client.HttpClientHelper;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TextHeaderSetter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import javax.annotation.Nullable;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.util.Collection;
import java.util.Arrays;
import lucee.runtime.tag.Lock;
import javax.servlet.jsp.tagext.Tag;
import lucee.commons.io.res.Resource;
import java.io.File;

public class LuceeExtensionImageTagInstrumentation extends TracerAwareInstrumentation {
    //org.lucee.extension.image.tag.Image#doStartTag
    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return named("org.lucee.extension.image.tag.Image");
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return named("doStartTag");
    }

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Arrays.asList("lucee", "image");
    }

    @Override
    public Class<?> getAdviceClass() {
        return CfLockAdvice.class;
    }
    @VisibleForAdvice
    public static class CfLockAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        private static void onBeforeExecute(
                @Advice.FieldValue(value="strAction") @Nullable String strAction,
                @Advice.FieldValue(value="oSource") @Nullable Object oSource,
                @Advice.FieldValue(value="base64") @Nullable boolean base64,
                @Advice.Local("span") @Nullable Span span) {

            if (tracer == null || tracer.getActive() == null) {
                return;
            }
            String str = "Unknown Image";
            if (oSource instanceof CharSequence) {
				str = oSource.toString();
                if (base64) {
                    str = "base64:" + str.substring(0,50);
                }
            } else if (oSource instanceof Resource) {
                try {
                    str = ((Resource)oSource).getCanonicalPath();
                } catch (Throwable e) {}
            } else if (oSource instanceof File) {
                try {
                    str = ((File)oSource).getCanonicalPath();
                } catch (Throwable e) {}
            }
            final AbstractSpan<?> parent = tracer.getActive();
            span = parent.createSpan()
                    .withName("cfImage " + strAction + " on " + str)
                    .withType("lucee")
                    .withSubtype("image")
                    .withAction(strAction);
            if (span != null) {
                span.activate();
            }
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onAfterExecute(@Advice.Local("span") @Nullable Span span,
                                          @Advice.Return @Nullable int retval,
                                          @Advice.Thrown @Nullable Throwable t) {
            if (span != null) {
                try {
                    span.captureException(t);
                } finally {
                    span.deactivate().end();
                }
            }
        }
    }
}
