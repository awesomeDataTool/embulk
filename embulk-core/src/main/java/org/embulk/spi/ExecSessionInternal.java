package org.embulk.spi;

import com.google.inject.Injector;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.embulk.EmbulkSystemProperties;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.DataSourceImpl;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.jruby.JRubyPluginSource;
import org.embulk.jruby.ScriptingContainerDelegate;
import org.embulk.plugin.InjectedPluginSource;
import org.embulk.plugin.PluginClassLoaderFactory;
import org.embulk.plugin.PluginClassLoaderFactoryImpl;
import org.embulk.plugin.PluginManager;
import org.embulk.plugin.PluginType;
import org.embulk.plugin.maven.MavenPluginSource;
import org.embulk.spi.time.Instants;
import org.embulk.spi.time.TimestampFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides access inside Embulk through a transactional session.
 *
 * <p>It is internal implementation of {@link ExecSession} although some methods, such as {@code newPlugin}, are to be
 * removed from {@link ExecSession} soon during Embulk v0.10 when {@link ExecSession} moves to {@code embulk-api} from
 * {@code embulk-core}. Even after the removal, they stay in this {@link ExecSessionInternal} to be called through
 * {@link ExecInternal}.
 */
public class ExecSessionInternal extends ExecSession {
    private static final Logger logger = LoggerFactory.getLogger(ExecSessionInternal.class);

    private static final DateTimeFormatter ISO8601_BASIC =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final Injector injector;
    private final EmbulkSystemProperties embulkSystemProperties;

    @Deprecated  // https://github.com/embulk/embulk/issues/1304
    private final org.embulk.config.ModelManager modelManager;

    private final PluginClassLoaderFactory pluginClassLoaderFactory;
    private final PluginManager pluginManager;
    private final BufferAllocator bufferAllocator;

    private final Instant transactionTime;
    private final TempFileSpace tempFileSpace;

    private final boolean preview;

    @Deprecated  // TODO: Remove it.
    private interface SessionTask extends Task {
        @Config("transaction_time")
        @ConfigDefault("null")
        Optional<String> getTransactionTime();
    }

    public static class Builder {
        private final Injector injector;
        private EmbulkSystemProperties embulkSystemProperties;
        private Set<String> parentFirstPackages;
        private Set<String> parentFirstResources;
        private Instant transactionTime;

        public Builder(Injector injector) {
            this.injector = injector;
            this.embulkSystemProperties = null;
            this.parentFirstPackages = null;
            this.parentFirstResources = null;
            this.transactionTime = null;
        }

        public Builder fromExecConfig(ConfigSource configSource) {
            final Optional<Instant> transactionTime =
                    toInstantFromString(configSource.get(String.class, "transaction_time", null));
            if (transactionTime.isPresent()) {
                this.transactionTime = transactionTime.get();
            }
            return this;
        }

        public Builder setEmbulkSystemProperties(final EmbulkSystemProperties embulkSystemProperties) {
            this.embulkSystemProperties = embulkSystemProperties;
            return this;
        }

        public Builder setParentFirstPackages(final Set<String> parentFirstPackages) {
            this.parentFirstPackages = Collections.unmodifiableSet(new HashSet<>(parentFirstPackages));
            return this;
        }

        public Builder setParentFirstResources(final Set<String> parentFirstResources) {
            this.parentFirstResources = Collections.unmodifiableSet(new HashSet<>(parentFirstResources));
            return this;
        }

        @Deprecated  // TODO: Add setTransactionTime(Instant) if needed. But no one looks using it. May not be needed.
        @SuppressWarnings("deprecation")  // https://github.com/embulk/embulk/issues/1292
        public Builder setTransactionTime(final org.embulk.spi.time.Timestamp timestamp) {
            logger.warn("ExecSessionInternal.Builder#setTransactionTime is deprecated. Set it via ExecSessionInternal.Builder#fromExecConfig.");
            this.transactionTime = timestamp.getInstant();
            return this;
        }

        public ExecSessionInternal build() {
            if (transactionTime == null) {
                transactionTime = Instant.now();
            }
            if (this.embulkSystemProperties == null) {
                logger.warn("EmbulkSystemProperties is not set when building ExecSessionInternal. "
                            + "Use ExecSessionInternal.Builder#setEmbulkSystemProperties.");
                this.embulkSystemProperties = this.injector.getInstance(EmbulkSystemProperties.class);
            }
            return new ExecSessionInternal(
                    this.injector,
                    this.transactionTime,
                    this.embulkSystemProperties,
                    this.parentFirstPackages,
                    this.parentFirstResources);
        }
    }

    public static Builder builderInternal(Injector injector) {
        return new Builder(injector);
    }

    @Deprecated
    @SuppressWarnings("deprecation")  // For the use of SessionTask.
    public ExecSessionInternal(Injector injector, ConfigSource configSource) {
        this(injector,
             configSource.loadConfig(SessionTask.class).getTransactionTime().flatMap(ExecSessionInternal::toInstantFromString).orElse(
                     Instant.now()),
             injector.getInstance(EmbulkSystemProperties.class),
             null,
             null);
        logger.warn("Constructing with ExecSession(Injector, ConfigSource) is deprecated. Use ExecSession.Builder instead.");
    }

    private ExecSessionInternal(
            final Injector injector,
            final Instant transactionTime,
            final EmbulkSystemProperties embulkSystemProperties,
            final Set<String> parentFirstPackages,
            final Set<String> parentFirstResources) {
        if (parentFirstPackages == null) {
            logger.warn("Parent-first packages are not set when building ExecSession. "
                        + "Use ExecSession.Builder#setParentFirstPackages.");
        }
        if (parentFirstResources == null) {
            logger.warn("Parent-first resources are not set when building ExecSession. "
                        + "Use ExecSession.Builder#setParentFirstResources.");
        }

        this.injector = injector;
        this.embulkSystemProperties = embulkSystemProperties;
        this.modelManager = getModelManagerFromInjector(injector);

        this.pluginClassLoaderFactory = PluginClassLoaderFactoryImpl.of(
                (parentFirstPackages != null) ? parentFirstPackages : Collections.unmodifiableSet(new HashSet<>()),
                (parentFirstResources != null) ? parentFirstResources : Collections.unmodifiableSet(new HashSet<>()));
        this.pluginManager = PluginManager.with(
                new InjectedPluginSource(injector),
                new MavenPluginSource(injector, embulkSystemProperties, pluginClassLoaderFactory),
                new JRubyPluginSource(injector.getInstance(ScriptingContainerDelegate.class), pluginClassLoaderFactory));

        this.bufferAllocator = injector.getInstance(BufferAllocator.class);

        this.transactionTime = transactionTime;

        final TempFileSpaceAllocator tempFileSpaceAllocator = injector.getInstance(TempFileSpaceAllocator.class);
        this.tempFileSpace = tempFileSpaceAllocator.newSpace(ISO8601_BASIC.format(this.transactionTime));

        this.preview = false;
    }

    private ExecSessionInternal(ExecSessionInternal copy, boolean preview) {
        this.injector = copy.injector;
        this.embulkSystemProperties = copy.embulkSystemProperties;
        this.modelManager = copy.modelManager;
        this.pluginClassLoaderFactory = copy.pluginClassLoaderFactory;
        this.pluginManager = copy.pluginManager;
        this.bufferAllocator = copy.bufferAllocator;

        this.transactionTime = copy.transactionTime;
        this.tempFileSpace = copy.tempFileSpace;

        this.preview = preview;
    }

    @Override
    public ExecSessionInternal forPreview() {
        return new ExecSessionInternal(this, true);
    }

    @Deprecated
    @Override
    @SuppressWarnings("deprecation")
    public ConfigSource getSessionExecConfig() {
        return newConfigSource()
                .set("transaction_time", Instants.toString(this.transactionTime));
    }

    @SuppressWarnings("deprecation")  // https://github.com/embulk/embulk/issues/1313
    @Override
    public Injector getInjector() {
        return injector;
    }

    @Deprecated
    @SuppressWarnings("deprecation")  // https://github.com/embulk/embulk/issues/1292
    @Override
    public org.embulk.spi.time.Timestamp getTransactionTime() {
        return org.embulk.spi.time.Timestamp.ofInstant(this.transactionTime);
    }

    @Override
    public Instant getTransactionTimeInstant() {
        return this.transactionTime;
    }

    @Override
    public String getTransactionTimeString() {
        return Instants.toString(this.transactionTime);
    }

    @Deprecated  // @see docs/design/slf4j.md
    @Override
    @SuppressWarnings("deprecation")
    public Logger getLogger(String name) {
        return LoggerFactory.getLogger(name);
    }

    @Deprecated  // @see docs/design/slf4j.md
    @Override
    @SuppressWarnings("deprecation")
    public Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }

    @Override
    public BufferAllocator getBufferAllocator() {
        return bufferAllocator;
    }

    @SuppressWarnings("deprecation")  // https://github.com/embulk/embulk/issues/1304
    @Override
    public org.embulk.config.ModelManager getModelManager() {
        return modelManager;
    }

    @SuppressWarnings("deprecation")  // https://github.com/embulk/embulk/issues/1309
    @Override
    public <T> T newPlugin(Class<T> iface, PluginType type) {
        return pluginManager.newPlugin(iface, type);
    }

    @Override
    public TaskReport newTaskReport() {
        return new DataSourceImpl(modelManager);
    }

    @Override
    public ConfigDiff newConfigDiff() {
        return new DataSourceImpl(modelManager);
    }

    @Override
    public ConfigSource newConfigSource() {
        return new DataSourceImpl(modelManager);
    }

    @Override
    public TaskSource newTaskSource() {
        return new DataSourceImpl(modelManager);
    }

    // To be removed by v0.10 or earlier.
    @Deprecated  // https://github.com/embulk/embulk/issues/936
    @SuppressWarnings("deprecation")
    @Override
    public TimestampFormatter newTimestampFormatter(String format, org.joda.time.DateTimeZone timezone) {
        return new TimestampFormatter(format, timezone);
    }

    @Override
    public TempFileSpace getTempFileSpace() {
        return tempFileSpace;
    }

    @Override
    public boolean isPreview() {
        return preview;
    }

    @Override
    public void cleanup() {
        this.pluginClassLoaderFactory.clear();
        tempFileSpace.cleanup();
    }

    @SuppressWarnings("deprecation")  // https://github.com/embulk/embulk/issues/1304
    private static org.embulk.config.ModelManager getModelManagerFromInjector(final Injector injector) {
        return injector.getInstance(org.embulk.config.ModelManager.class);
    }

    private static Optional<Instant> toInstantFromString(final String string) {
        if (string == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(Instants.parseInstant(string));
        } catch (final NumberFormatException ex) {
            logger.warn("\"transaction_time\" is in an invalid format: '" + string + "'. "
                        + "The transaction time is set to the present.", ex);
            return Optional.empty();
        } catch (final IllegalStateException ex) {
            logger.error("Unexpected failure in parsing \"transaction_time\": '" + string + "'", ex);
            throw ex;
        }
    }
}
