package org.javers.core.metamodel.type;

import org.javers.common.collections.Optional;
import org.javers.common.collections.Primitives;
import org.javers.common.exception.JaversException;
import org.javers.common.exception.JaversExceptionCode;
import org.javers.common.reflection.ReflectionUtil;
import org.javers.core.metamodel.clazz.ClientsClassDefinition;
import org.javers.core.metamodel.clazz.Entity;
import org.javers.core.metamodel.clazz.ManagedClass;
import org.javers.core.metamodel.clazz.ValueObject;
import org.javers.core.metamodel.property.Property;
import org.joda.time.LocalDate;
import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.javers.common.reflection.ReflectionUtil.extractClass;
import static org.javers.common.validation.Validate.argumentIsNotNull;

/**
 * Maps Java types into Javers types
 *
 * @author bartosz walacik
 */
public class TypeMapper {
    private static final Logger logger = LoggerFactory.getLogger(TypeMapper.class);

    private final ValueType OBJECT_TYPE = new ValueType(Object.class);
    private final TypeFactory typeFactory;
    private final Map<Type, JaversType> mappedTypes;
    private final DehydratedTypeFactory dehydratedTypeFactory = new DehydratedTypeFactory(this);

    public TypeMapper(TypeFactory typeFactory) {
        this.typeFactory = typeFactory;

        mappedTypes = new ConcurrentHashMap<>();

        //primitives & boxes
        for (Class primitiveOrBox : Primitives.getPrimitiveAndBoxTypes()) {
            registerPrimitiveType(primitiveOrBox);
        }

        //String & Enum
        registerPrimitiveType(String.class);
        registerPrimitiveType(Enum.class);

        //array
        addType(new ArrayType(Object[].class));

        //well known Value types
        registerValueType(LocalDateTime.class);
        registerValueType(LocalDate.class);
        registerValueType(BigDecimal.class);
        registerValueType(Date.class);
        registerValueType(ThreadLocal.class);
        registerValueType(URI.class);
        registerValueType(URL.class);
        registerValueType(Path.class);


        //Collections
        addType(new SetType(Set.class));
        addType(new ListType(List.class));

        //& Maps
        addType(new MapType(Map.class));

        // bootstrap phase 2: add-ons
        if (ReflectionUtil.isJava8runtime()){
            addType(new OptionalType());
        }
    }

    public MapContentType getMapContentType(MapType mapType){
        JaversType keyType = getJaversType(mapType.getKeyType());
        JaversType valueType = getJaversType(mapType.getValueType());
        return new MapContentType(keyType, valueType);
    }

    /**
     * for change appenders
     */
    public MapContentType getMapContentType(ContainerType containerType){
        JaversType keyType = getJaversType(Integer.class);
        JaversType valueType = getJaversType(containerType.getItemType());
        return new MapContentType(keyType, valueType);
    }

    /**
     * returns mapped type or spawns new one from prototype
     * or infers new one using default mapping
     */
    public JaversType getJaversType(Type javaType) {
        argumentIsNotNull(javaType);

        if (javaType == Object.class){
            return OBJECT_TYPE;
        }

        JaversType jType = mappedTypes.get(javaType);
        if (jType != null) {
            return jType;
        }

        return mappedTypes.computeIfAbsent(javaType, new Function<Type, JaversType>() {
            public JaversType apply(Type type) {
                return infer(type);
            }
        });
    }

    private JaversType infer(Type javaType) {
        argumentIsNotNull(javaType);
        JaversType prototype = findNearestAncestor(javaType);
        JaversType newType = typeFactory.infer(javaType, Optional.fromNullable(prototype));

        inferIdPropertyTypeForEntityIfNeeed(newType);

        return newType;
    }

    /**
     * @throws JaversException CLASS_NOT_MANAGED if given javaClass is NOT mapped to {@link ManagedType}
     */
    public ManagedType getJaversManagedType(Class javaType) {
        JaversType javersType = getJaversType(javaType);

        if (!(javersType instanceof  ManagedType)){
            throw new JaversException(JaversExceptionCode.CLASS_NOT_MANAGED,
                                      javaType.getName(),
                                      javersType.getClass().getSimpleName()) ;
        }

        return (ManagedType)javersType;
    }

    public <T extends JaversType> T getPropertyType(Property property){
        argumentIsNotNull(property);
        return (T) getJaversType(property.getGenericType());
    }

    private void registerPrimitiveType(Class<?> primitiveClass) {
        addType(new PrimitiveType(primitiveClass));
    }

    public void registerClientsClass(ClientsClassDefinition def) {
        addType(typeFactory.createFromDefinition(def));
    }

    public void registerValueType(Class<?> valueCLass) {
        addType(new ValueType(valueCLass));
    }

    public void registerCustomType(Class<?> customCLass) {
        addType(new CustomType(customCLass));
    }

    public boolean isValueObject(Type type) {
        JaversType jType  = getJaversType(type);
        return  jType instanceof ValueObjectType;
    }

    /**
     * Dehydrated type for JSON representation
     */
    public Type getDehydratedType(Type type) {
        return dehydratedTypeFactory.build(type);
    }

    /**
     * if given javaClass is mapped to {@link ManagedType}
     * returns {@link ManagedType#getManagedClass()}
     *
     * @throws JaversException MANAGED_CLASS_MAPPING_ERROR
     */
    public <T extends ManagedClass> T getManagedClass(Class javaClass, Class<T> expectedType) {
        ManagedType mType = getJaversManagedType(javaClass);

        if (mType.getManagedClass().getClass().equals( expectedType)) {
            return (T)mType.getManagedClass();
        }
        else {
            throw new JaversException(JaversExceptionCode.MANAGED_CLASS_MAPPING_ERROR,
                    javaClass,
                    mType.getManagedClass().getSimpleName(),
                    expectedType.getSimpleName());
        }
    }

    public ValueObject getChildValueObject(Entity owner, String voPropertyName) {
        JaversType javersType = getJaversType( owner.getProperty(voPropertyName).getGenericType() );

        if (javersType instanceof ValueObjectType) {
            return ((ValueObjectType) javersType).getManagedClass();
        }

        if (javersType instanceof ContainerType) {
            JaversType contentType  = getJaversType(((ContainerType) javersType).getItemType());
            if (contentType instanceof ValueObjectType){
                return ((ValueObjectType)contentType).getManagedClass();
            }
        }

        throw new JaversException(JaversExceptionCode.CANT_EXTRACT_CHILD_VALUE_OBJECT,
                  owner.getName()+"."+voPropertyName,
                  javersType);

    }

    //-- private

    private void addType(JaversType jType) {
        mappedTypes.putIfAbsent(jType.getBaseJavaType(), jType);
        inferIdPropertyTypeForEntityIfNeeed(jType);
    }

    /**
     * if type of given id-property is not already mapped, maps it as ValueType
     */
    private void inferIdPropertyTypeForEntityIfNeeed(JaversType jType) {
        argumentIsNotNull(jType);
        if (! (jType instanceof EntityType)){
            return;
        }

        EntityType entityType = (EntityType) jType;
        mappedTypes.computeIfAbsent(entityType.getIdPropertyGenericType(), new Function<Type, JaversType>() {
            public JaversType apply(Type type) {
                return typeFactory.inferIdPropertyTypeAsValue(type);
            }
        });
    }

    private JaversType findNearestAncestor(Type javaType) {
        Class javaClass = extractClass(javaType);
        List<DistancePair> distances = new ArrayList<>();

        for (JaversType javersType : mappedTypes.values()) {
            DistancePair distancePair = new DistancePair(javaClass, javersType);

            //this is due too spoiled Java Array reflection API
            if (javaClass.isArray()) {
                return getJaversType(Object[].class);
            }

            //just to better speed
            if (distancePair.getDistance() == 1) {
                return distancePair.getJaversType();
            }

            distances.add(distancePair);
        }

        Collections.sort(distances);

        if (distances.get(0).isMax()){
            return null;
        }

        return distances.get(0).getJaversType();
    }

}
