/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.impl.internal.spi.serialization;

import org.ehcache.impl.config.serializer.DefaultSerializationProviderConfiguration;
import org.ehcache.impl.config.serializer.DefaultSerializerConfiguration;
import org.ehcache.CachePersistenceException;
import org.ehcache.impl.serialization.ByteArraySerializer;
import org.ehcache.impl.serialization.CharSerializer;
import org.ehcache.core.internal.service.ServiceLocator;
import org.ehcache.impl.serialization.CompactJavaSerializer;
import org.ehcache.impl.serialization.DoubleSerializer;
import org.ehcache.impl.serialization.FloatSerializer;
import org.ehcache.impl.serialization.IntegerSerializer;
import org.ehcache.impl.serialization.LongSerializer;
import org.ehcache.impl.serialization.StringSerializer;
import org.ehcache.spi.persistence.PersistableResourceService;
import org.ehcache.spi.persistence.PersistableResourceService.PersistenceSpaceIdentifier;
import org.ehcache.spi.serialization.StatefulSerializer;
import org.ehcache.spi.service.ServiceProvider;
import org.ehcache.spi.serialization.SerializationProvider;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.UnsupportedTypeException;
import org.ehcache.core.spi.service.FileBasedPersistenceContext;
import org.ehcache.core.spi.service.LocalPersistenceService;
import org.ehcache.spi.service.Service;
import org.ehcache.spi.service.ServiceConfiguration;
import org.ehcache.core.internal.util.ConcurrentWeakIdentityHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ehcache.core.internal.service.ServiceLocator.findSingletonAmongst;

/**
 * @author Ludovic Orban
 */
public class DefaultSerializationProvider implements SerializationProvider {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSerializationProvider.class);

  private final TransientProvider transientProvider;
  private final PersistentProvider persistentProvider;

  final ConcurrentWeakIdentityHashMap<Serializer<?>, AtomicInteger> providedVsCount = new ConcurrentWeakIdentityHashMap<Serializer<?>, AtomicInteger>();
  final Set<Serializer<?>> instantiated = Collections.newSetFromMap(new ConcurrentWeakIdentityHashMap<Serializer<?>, Boolean>());

  public DefaultSerializationProvider(DefaultSerializationProviderConfiguration configuration) {
    if (configuration != null) {
      transientProvider = new TransientProvider(configuration.getTransientSerializers());
      persistentProvider = new PersistentProvider(configuration.getPersistentSerializers());
    } else {
      transientProvider = new TransientProvider(Collections.<Class<?>, Class<? extends Serializer<?>>>emptyMap());
      persistentProvider = new PersistentProvider(Collections.<Class<?>, Class<? extends Serializer<?>>>emptyMap());
    }
  }

  @Override
  public <T> Serializer<T> createKeySerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
    Serializer<T> serializer = (Serializer<T>)getUserProvidedSerializer(find(DefaultSerializerConfiguration.Type.KEY, configs));
    if (serializer == null) {
      if (findSingletonAmongst(PersistenceSpaceIdentifier.class, (Object[])configs) == null) {
        serializer = transientProvider.createKeySerializer(clazz, classLoader, configs);
      } else {
        serializer = persistentProvider.createKeySerializer(clazz, classLoader, configs);
      }
      instantiated.add(serializer);
    }
    updateProvidedInstanceCounts(serializer);
    return serializer;
  }

  @Override
  public <T> Serializer<T> createValueSerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
    Serializer<T> serializer = (Serializer<T>)getUserProvidedSerializer(find(DefaultSerializerConfiguration.Type.VALUE, configs));
    if (serializer == null) {
      if (findSingletonAmongst(PersistenceSpaceIdentifier.class, (Object[])configs) == null) {
        serializer = transientProvider.createValueSerializer(clazz, classLoader, configs);
      } else {
        serializer = persistentProvider.createValueSerializer(clazz, classLoader, configs);
      }
      instantiated.add(serializer);
    }
    updateProvidedInstanceCounts(serializer);
    return serializer;
  }

  private void updateProvidedInstanceCounts(Serializer<?> serializer) {
    AtomicInteger currentCount = providedVsCount.putIfAbsent(serializer, new AtomicInteger(1));
    if(currentCount != null) {
      currentCount.incrementAndGet();
    }
  }

  @Override
  public void releaseSerializer(final Serializer<?> serializer) throws IOException {
    AtomicInteger currentCount = providedVsCount.get(serializer);
    if(currentCount != null) {
      if(currentCount.decrementAndGet() < 0) {
        currentCount.incrementAndGet();
        throw new IllegalArgumentException("Given serializer:" + serializer.getClass().getName() + " is not managed by this provider");
      }
    } else {
      throw new IllegalArgumentException("Given serializer:" + serializer.getClass().getName() + " is not managed by this provider");
    }

    if(instantiated.remove(serializer)) {
      if (serializer instanceof Closeable) {
        ((Closeable) serializer).close();
      }
    }
  }

  @Override
  public void start(ServiceProvider<Service> serviceProvider) {
    transientProvider.start(serviceProvider);
    persistentProvider.start(serviceProvider);
  }

  @Override
  public void stop() {
    // no-op
  }

  private static <T> void addDefaultSerializerIfNoneRegistered(Map<Class<?>, Class<? extends Serializer<?>>> serializers, Class<T> clazz, Class<? extends Serializer<T>> serializerClass) {
    if (!serializers.containsKey(clazz)) {
      serializers.put(clazz, serializerClass);
    }
  }

  static class TransientProvider extends AbstractProvider {

    public TransientProvider(Map<Class<?>, Class<? extends Serializer<?>>> serializers) {
      super(serializers);
    }

    @Override
    protected <T> Serializer<T> createSerializer(String suffix, Class<T> clazz, ClassLoader classLoader, DefaultSerializerConfiguration<T> config, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      Class<? extends Serializer<T>> klazz = getSerializerClassFor(clazz, config, classLoader);
      try {
        return constructSerializer(clazz, klazz.getConstructor(ClassLoader.class), classLoader);
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(klazz + " does not meet the constructor requirement for transient caches", e);
      }
    }

    public void start(ServiceProvider<Service> serviceProvider) {
      addDefaultSerializerIfNoneRegistered(serializers, Serializable.class, (Class) CompactJavaSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Long.class, LongSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Integer.class, IntegerSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Float.class, FloatSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Double.class, DoubleSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Character.class, CharSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, String.class, StringSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, byte[].class, ByteArraySerializer.class);
    }
  }

  static class PersistentProvider extends AbstractProvider {

    private ServiceProvider<Service> serviceProvider;

    private PersistentProvider(Map<Class<?>, Class<? extends Serializer<?>>> serializers) {
      super(serializers);
    }

    @Override
    protected <T> Serializer<T> createSerializer(String suffix, Class<T> clazz, ClassLoader classLoader, DefaultSerializerConfiguration<T> config, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      Class<? extends Serializer<T>> klazz = getSerializerClassFor(clazz, config, classLoader);
      String errorMessage = klazz + " does not meet the constructor requirements for persistent caches";

      if (StatefulSerializer.class.isAssignableFrom(klazz)) {
        try {
          Constructor<? extends Serializer<T>> constructor = klazz.getConstructor(ClassLoader.class);
          return constructSerializer(clazz, constructor, classLoader);
        } catch (NoSuchMethodException e) {
          throw new RuntimeException(errorMessage, e);
        }
      } else {
        PersistenceSpaceIdentifier<? extends PersistableResourceService> space = findSingletonAmongst(PersistenceSpaceIdentifier.class, (Object[]) configs);
        PersistableResourceService service = serviceProvider.getService(space.getServiceType());

        if (service instanceof LocalPersistenceService) {
          try {
            Constructor<? extends Serializer<T>> constructor = klazz.getConstructor(ClassLoader.class, FileBasedPersistenceContext.class);
            String subSpaceName = DefaultSerializationProvider.class.getSimpleName() + suffix;
            FileBasedPersistenceContext context = ((LocalPersistenceService) service).createPersistenceContextWithin(space, subSpaceName);
            return constructSerializer(clazz, constructor, classLoader, context);
          } catch (NoSuchMethodException nsmex) {
            try {
              Constructor<? extends Serializer<T>> constructor = klazz.getConstructor(ClassLoader.class);
              return constructSerializer(clazz, constructor, classLoader);
            } catch (NoSuchMethodException e) {
              throw new RuntimeException(errorMessage, e);
            }
          } catch (CachePersistenceException cpex) {
            throw new RuntimeException(cpex);
          }
        } else {
          try {
            Constructor<? extends Serializer<T>> constructor = klazz.getConstructor(ClassLoader.class);
            return constructSerializer(clazz, constructor, classLoader);
          } catch (NoSuchMethodException e) {
            throw new RuntimeException(errorMessage, e);
          }
        }
      }
    }

    public void start(ServiceProvider<Service> serviceProvider) {
      this.serviceProvider = serviceProvider;
      addDefaultSerializerIfNoneRegistered(serializers, Serializable.class, (Class) CompactJavaSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Long.class, LongSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Integer.class, IntegerSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Float.class, FloatSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Double.class, DoubleSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, Character.class, CharSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, String.class, StringSerializer.class);
      addDefaultSerializerIfNoneRegistered(serializers, byte[].class, ByteArraySerializer.class);
    }

  }

  static abstract class AbstractProvider  {

    protected final Map<Class<?>, Class<? extends Serializer<?>>> serializers;

    private AbstractProvider(Map<Class<?>, Class<? extends Serializer<?>>> serializers) {
      this.serializers = new LinkedHashMap<Class<?>, Class<? extends Serializer<?>>>(serializers);
    }

    public <T> Serializer<T> createKeySerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      DefaultSerializerConfiguration<T> conf = find(DefaultSerializerConfiguration.Type.KEY, configs);
      return createSerializer("-Key", clazz, classLoader, conf, configs);
    }

    public <T> Serializer<T> createValueSerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      DefaultSerializerConfiguration<T> conf = find(DefaultSerializerConfiguration.Type.VALUE, configs);
      return createSerializer("-Value", clazz, classLoader, conf, configs);
    }

    protected abstract <T> Serializer<T> createSerializer(String suffix, Class<T> clazz, ClassLoader classLoader, DefaultSerializerConfiguration<T> config, ServiceConfiguration<?>... configs) throws UnsupportedTypeException;

    protected <T> Class<? extends Serializer<T>> getSerializerClassFor(Class<T> clazz, DefaultSerializerConfiguration<T> config, ClassLoader classLoader) throws UnsupportedTypeException {
      if (config != null) {
        Class<? extends Serializer<T>> configured = config.getClazz();
        if (configured != null) {
          return configured;
        }
      }

      Class<? extends Serializer<T>> direct = (Class<? extends Serializer<T>>) serializers.get(clazz);
      if (direct != null) {
        return direct;
      }
      for (Map.Entry<Class<?>, Class<? extends Serializer<?>>> entry : serializers.entrySet()) {
        if (entry.getKey().isAssignableFrom(clazz)) {
          return (Class<? extends Serializer<T>>) entry.getValue();
        }
      }
      throw new UnsupportedTypeException("No serializer found for type '" + clazz.getName() + "'");
    }

    protected <T> Serializer<T> constructSerializer(Class<T> clazz, Constructor<? extends Serializer<T>> constructor, Object ... args) {
      try {
        Serializer<T> serializer = constructor.newInstance(args);
        LOG.debug("Serializer for <{}> : {}", clazz.getName(), serializer);
        return serializer;
      } catch (InstantiationException e) {
        throw new RuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      } catch (IllegalArgumentException e) {
        throw new AssertionError(e);
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static <T> Serializer<T> getUserProvidedSerializer(DefaultSerializerConfiguration<T> conf) {
    if(conf != null) {
      Serializer<T> instance = conf.getInstance();
      if(instance != null) {
        return instance;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static <T> DefaultSerializerConfiguration<T> find(DefaultSerializerConfiguration.Type type, ServiceConfiguration<?>... serviceConfigurations) {
    DefaultSerializerConfiguration<T> result = null;

    Collection<DefaultSerializerConfiguration> serializationProviderConfigurations = ServiceLocator.findAmongst(DefaultSerializerConfiguration.class, (Object[]) serviceConfigurations);
    for (DefaultSerializerConfiguration serializationProviderConfiguration : serializationProviderConfigurations) {
      if (serializationProviderConfiguration.getType() == type) {
        if (result != null) {
          throw new IllegalArgumentException("Duplicate " + type + " serialization provider : " + serializationProviderConfiguration);
        }
        result = serializationProviderConfiguration;
      }
    }

    return result;
  }
}
