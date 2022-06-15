/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import jdk.internal.reflect.ConstantPool;
import jdk.vm.ci.meta.JavaConstant;
import sun.reflect.annotation.AnnotationParser;
import sun.reflect.annotation.AnnotationType;
import sun.reflect.annotation.TypeNotPresentExceptionProxy;

public final class AnnotationValue implements AnnotationMemberValue {
    private static final Map<AnnotationValue, Annotation> resolvedAnnotations = new ConcurrentHashMap<>();

    final Class<? extends Annotation> type;
    final Map<String, AnnotationMemberValue> members;

    @SuppressWarnings("unchecked")
    static AnnotationValue extract(ByteBuffer buf, ConstantPool cp, Class<?> container, boolean exceptionOnMissingAnnotationClass, boolean skip) {
        boolean skipMembers = skip;
        Object typeOrException = AnnotationMetadata.extractType(buf, cp, container, skip);
        if (typeOrException instanceof TypeNotPresentExceptionProxy) {
            if (exceptionOnMissingAnnotationClass) {
                TypeNotPresentExceptionProxy proxy = (TypeNotPresentExceptionProxy) typeOrException;
                throw new TypeNotPresentException(proxy.typeName(), proxy.getCause());
            }
            skipMembers = true;
        }

        int numMembers = buf.getShort() & 0xFFFF;
        Map<String, AnnotationMemberValue> memberValues = new LinkedHashMap<>();
        for (int i = 0; i < numMembers; i++) {
            String memberName = AnnotationMetadata.extractString(buf, cp, skipMembers);
            AnnotationMemberValue memberValue = AnnotationMemberValue.extract(buf, cp, container, skipMembers);
            if (!skipMembers) {
                memberValues.put(memberName, memberValue);
            }
        }

        if (skipMembers) {
            return null;
        }
        Class<? extends Annotation> type = (Class<? extends Annotation>) typeOrException;
        return new AnnotationValue(type, memberValues);
    }

    AnnotationValue(Annotation annotation) {
        this.type = annotation.annotationType();
        this.members = new LinkedHashMap<>();
        AnnotationType annotationType = AnnotationType.getInstance(type);
        annotationType.members().forEach((memberName, memberAccessor) -> {
            AnnotationMemberValue memberValue;
            try {
                memberValue = AnnotationMemberValue.from(annotationType.memberTypes().get(memberName), memberAccessor.invoke(annotation));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AnnotationMetadata.AnnotationExtractionError(e);
            }
            Object memberDefault = annotationType.memberDefaults().get(memberName);
            if (!memberValue.equals(memberDefault)) {
                members.put(memberName, memberValue);
            }
        });
    }

    private AnnotationValue(Class<? extends Annotation> type, Map<String, AnnotationMemberValue> members) {
        this.type = type;
        this.members = members;
    }

    public Class<? extends Annotation> getType() {
        return type;
    }

    public int getMemberCount() {
        return members.size();
    }

    public void forEachMember(BiConsumer<String, AnnotationMemberValue> callback) {
        members.forEach(callback);
    }

    @Override
    public List<Class<?>> getTypes() {
        List<Class<?>> types = new ArrayList<>();
        types.add(type);
        for (AnnotationMemberValue memberValue : members.values()) {
            types.addAll(memberValue.getTypes());
        }
        return types;
    }

    @Override
    public List<String> getStrings() {
        List<String> strings = new ArrayList<>();
        members.forEach((memberName, memberValue) -> {
            strings.add(memberName);
            strings.addAll(memberValue.getStrings());
        });
        return strings;
    }

    @Override
    public List<JavaConstant> getExceptionProxies() {
        List<JavaConstant> exceptionProxies = new ArrayList<>();
        for (AnnotationMemberValue memberValue : members.values()) {
            exceptionProxies.addAll(memberValue.getExceptionProxies());
        }
        return exceptionProxies;
    }

    @Override
    public char getTag() {
        return '@';
    }

    @Override
    public Object get(Class<?> memberType) {
        Annotation value = resolvedAnnotations.computeIfAbsent(this, annotationValue -> {
            AnnotationType annotationType = AnnotationType.getInstance(annotationValue.type);
            Map<String, Object> memberValues = new LinkedHashMap<>(annotationType.memberDefaults());
            annotationValue.members.forEach((memberName, memberValue) -> memberValues.put(memberName, memberValue.get(annotationType.memberTypes().get(memberName))));
            return AnnotationParser.annotationForMap(type, memberValues);
        });
        return AnnotationMetadata.checkResult(value, memberType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AnnotationValue that = (AnnotationValue) o;
        return Objects.equals(type, that.type) && members.equals(that.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, members);
    }
}
