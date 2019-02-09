/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.ActionConfiguration;
import org.gradle.api.NonExtensible;
import org.gradle.api.artifacts.transform.ArtifactTransform;
import org.gradle.api.artifacts.transform.ArtifactTransformAction;
import org.gradle.api.artifacts.transform.ArtifactTransformSpec;
import org.gradle.api.artifacts.transform.ParameterizedArtifactTransformSpec;
import org.gradle.api.artifacts.transform.PrimaryInput;
import org.gradle.api.artifacts.transform.PrimaryInputDependencies;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.transform.VariantTransform;
import org.gradle.api.artifacts.transform.VariantTransformConfigurationException;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.DefaultActionConfiguration;
import org.gradle.api.internal.artifacts.VariantTransformRegistry;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.tasks.properties.InspectionSchemeFactory;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.api.internal.tasks.properties.TypeMetadataStore;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.internal.Cast;
import org.gradle.internal.classloader.ClassLoaderHierarchyHasher;
import org.gradle.internal.instantiation.InstantiationScheme;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.snapshot.ValueSnapshotter;

import javax.annotation.Nullable;
import java.util.List;

public class DefaultVariantTransformRegistry implements VariantTransformRegistry {
    private static final Object[] NO_PARAMETERS = new Object[0];
    private final List<Registration> transforms = Lists.newArrayList();
    private final ImmutableAttributesFactory immutableAttributesFactory;
    private final IsolatableFactory isolatableFactory;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final ServiceRegistry services;
    private final InstantiatorFactory instantiatorFactory;
    private final TransformerInvoker transformerInvoker;
    private final ValueSnapshotter valueSnapshotter;
    private final PropertyWalker parametersObjectPropertyWalker;
    private final TypeMetadataStore actionMetadataStore;
    private final InstantiationScheme instantiationScheme;

    public DefaultVariantTransformRegistry(InstantiatorFactory instantiatorFactory, ImmutableAttributesFactory immutableAttributesFactory, IsolatableFactory isolatableFactory, ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ServiceRegistry services, TransformerInvoker transformerInvoker, ValueSnapshotter valueSnapshotter, InspectionSchemeFactory inspectionSchemeFactory) {
        this.instantiatorFactory = instantiatorFactory;
        this.immutableAttributesFactory = immutableAttributesFactory;
        this.isolatableFactory = isolatableFactory;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.services = services;
        this.transformerInvoker = transformerInvoker;
        this.valueSnapshotter = valueSnapshotter;
        this.instantiationScheme = instantiatorFactory.injectScheme(ImmutableSet.of(PrimaryInput.class, PrimaryInputDependencies.class, TransformParameters.class));
        this.parametersObjectPropertyWalker = inspectionSchemeFactory.inspectionScheme(ImmutableSet.of(Input.class, InputFile.class, InputFiles.class, InputDirectory.class, Classpath.class, CompileClasspath.class, Nested.class)).getPropertyWalker();
        this.actionMetadataStore = inspectionSchemeFactory.inspectionScheme(ImmutableSet.of(PrimaryInput.class, PrimaryInputDependencies.class, TransformParameters.class)).getMetadataStore();
    }

    @Override
    public void registerTransform(Action<? super VariantTransform> registrationAction) {
        UntypedRegistration registration = instantiatorFactory.decorateLenient().newInstance(UntypedRegistration.class, immutableAttributesFactory, instantiatorFactory);
        registrationAction.execute(registration);

        validateActionType(registration.actionType);
        validateAttributes(registration);

        Object[] parameters = registration.getTransformParameters();
        Registration finalizedRegistration = DefaultTransformationRegistration.create(registration.from.asImmutable(), registration.to.asImmutable(), registration.actionType, parameters, isolatableFactory, classLoaderHierarchyHasher, instantiatorFactory, transformerInvoker);
        transforms.add(finalizedRegistration);
    }

    @Override
    public <T> void registerTransform(Class<T> parameterType, Action<? super ParameterizedArtifactTransformSpec<T>> registrationAction) {
        // TODO - should decorate
        T parameterObject = instantiatorFactory.inject(services).newInstance(parameterType);
        TypedRegistration<T> registration = Cast.uncheckedNonnullCast(instantiatorFactory.decorateLenient().newInstance(TypedRegistration.class, parameterObject, immutableAttributesFactory));
        registrationAction.execute(registration);

        register(registration, registration.actionType, parameterObject);
    }

    @Override
    public <T extends ArtifactTransformAction> void registerTransformAction(Class<T> actionType, Action<? super ArtifactTransformSpec> registrationAction) {
        ActionRegistration registration = instantiatorFactory.decorateLenient().newInstance(ActionRegistration.class, immutableAttributesFactory);
        registrationAction.execute(registration);

        register(registration, actionType, null);
    }

    private <T> void register(RecordingRegistration registration, Class<? extends ArtifactTransformAction> actionType, @Nullable T parameterObject) {
        validateActionType(actionType);
        validateAttributes(registration);

        Registration finalizedRegistration = DefaultTransformationRegistration.create(registration.from.asImmutable(), registration.to.asImmutable(), actionType, parameterObject, isolatableFactory, classLoaderHierarchyHasher, instantiationScheme, transformerInvoker, valueSnapshotter, parametersObjectPropertyWalker, actionMetadataStore.getTypeMetadata(actionType));
        transforms.add(finalizedRegistration);
    }

    private <T> void validateActionType(@Nullable Class<T> actionType) {
        if (actionType == null) {
            throw new VariantTransformConfigurationException("Could not register transform: an artifact transform action must be provided.");
        }
    }

    private <T> void validateAttributes(RecordingRegistration registration) {
        if (registration.to.isEmpty()) {
            throw new VariantTransformConfigurationException("Could not register transform: at least one 'to' attribute must be provided.");
        }
        if (registration.from.isEmpty()) {
            throw new VariantTransformConfigurationException("Could not register transform: at least one 'from' attribute must be provided.");
        }
        if (!registration.from.keySet().containsAll(registration.to.keySet())) {
            throw new VariantTransformConfigurationException("Could not register transform: each 'to' attribute must be included as a 'from' attribute.");
        }
    }

    public Iterable<Registration> getTransforms() {
        return transforms;
    }

    public static abstract class RecordingRegistration implements ArtifactTransformSpec {
        final AttributeContainerInternal from;
        final AttributeContainerInternal to;

        public RecordingRegistration(ImmutableAttributesFactory immutableAttributesFactory) {
            from = immutableAttributesFactory.mutable();
            to = immutableAttributesFactory.mutable();
        }

        public AttributeContainer getFrom() {
            return from;
        }

        public AttributeContainer getTo() {
            return to;
        }
    }

    @NonExtensible
    public static class UntypedRegistration extends RecordingRegistration implements VariantTransform {
        private Action<? super ActionConfiguration> configAction;
        private final InstantiatorFactory instantiatorFactory;
        Class<? extends ArtifactTransform> actionType;

        public UntypedRegistration(ImmutableAttributesFactory immutableAttributesFactory, InstantiatorFactory instantiatorFactory) {
            super(immutableAttributesFactory);
            this.instantiatorFactory = instantiatorFactory;
        }

        @Override
        public void artifactTransform(Class<? extends ArtifactTransform> type) {
            artifactTransform(type, null);
        }

        @Override
        public void artifactTransform(Class<? extends ArtifactTransform> type, @Nullable Action<? super ActionConfiguration> config) {
            if (this.actionType != null) {
                throw new VariantTransformConfigurationException("Could not register transform: only one ArtifactTransform may be provided for registration.");
            }
            this.actionType = type;
            this.configAction = config;
        }

        Object[] getTransformParameters() {
            if (configAction == null) {
                return NO_PARAMETERS;
            }
            ActionConfiguration config = instantiatorFactory.decorateLenient().newInstance(DefaultActionConfiguration.class);
            configAction.execute(config);
            return config.getParams();
        }
    }

    @NonExtensible
    public static class TypedRegistration<T> extends RecordingRegistration implements ParameterizedArtifactTransformSpec<T> {
        private final T parameterObject;
        Class<? extends ArtifactTransformAction> actionType;

        public TypedRegistration(T parameterObject, ImmutableAttributesFactory immutableAttributesFactory) {
            super(immutableAttributesFactory);
            this.parameterObject = parameterObject;
            TransformAction transformAction = parameterObject.getClass().getAnnotation(TransformAction.class);
            if (transformAction != null) {
                actionType = transformAction.value();
            }
        }

        @Override
        public T getParameters() {
            return parameterObject;
        }

        @Override
        public void parameters(Action<? super T> action) {
            action.execute(parameterObject);
        }
    }

    @NonExtensible
    public static class ActionRegistration extends RecordingRegistration {

        public ActionRegistration(ImmutableAttributesFactory immutableAttributesFactory) {
            super(immutableAttributesFactory);
        }
    }
}
