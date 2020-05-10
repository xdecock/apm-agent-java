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

import co.elastic.apm.agent.bci.ElasticApmInstrumentation;
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

public class LuceeLockInstrumentation extends ElasticApmInstrumentation {
    // lucee.runtime.tag.Lock#doStartTag
    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return named("lucee.runtime.tag.Lock");
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return named("doStartTag");
    }

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Arrays.asList("lucee", "lock");
    }

    @Override
    public Class<?> getAdviceClass() {
        return CfLockAdvice.class;
    }
    @VisibleForAdvice
    public static class CfLockAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        private static void onBeforeExecute(
                @Advice.FieldValue(value="scope") @Nullable int scope,
                @Advice.FieldValue(value="name") @Nullable String name,
                @Advice.FieldValue(value="type") @Nullable int type,
                @Advice.FieldValue(value="id") @Nullable String id,
                @Advice.Local("span") @Nullable Span span) {

            if (tracer == null || tracer.getActive() == null) {
                return;
            }
            String lockName = "unknown";
            switch(scope) {
                case 1: // SCOPE_SERVER
                    lockName = "scoped:server";
                    break;
                case 2: // SCOPE_APPLICATION
                    lockName = "scoped:application";
                    break;
                case 3: // SCOPE_SESSION
                    lockName = "scoped:session";
                    break;
                case 4: // SCOPE_REQUEST
                    lockName = "scoped:request";
                    break;
                default:
                    if (name != null) {
                        lockName = "named:"+name;
                    } else {
                        lockName = "anonymous:id-"+id;
                    }
            }
            final AbstractSpan<?> parent = tracer.getActive();
            span = parent.createSpan()
                    .withName("CFLock " + lockName)
                    .withType("lucee")
                    .withSubtype("lock")
                    .withAction((type==0)?"shared":"exclusive");
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
                    if (retval == Tag.EVAL_BODY_INCLUDE) {
                        // success
                    } else {
                        // Failure
                    }
                    span.captureException(t);
                } finally {
                    span.deactivate().end();
                }
            }
        }
    }
}
