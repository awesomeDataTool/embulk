package org.embulk.standards;

import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.ColumnVisitor;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInput;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.ParserPlugin;
import org.embulk.spi.Schema;
import org.embulk.spi.SchemaConfig;
import org.embulk.spi.json.JsonParseException;
import org.embulk.spi.json.JsonParser;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.time.TimestampParser;
import org.embulk.spi.type.Types;
import org.embulk.spi.util.FileInputInputStream;
import org.embulk.spi.util.Timestamps;
import org.msgpack.core.Preconditions;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonParserPlugin implements ParserPlugin {

    public JsonParserPlugin() {
        this.jsonParser = new JsonParser();
    }

    public enum InvalidEscapeStringPolicy {
        PASSTHROUGH("PASSTHROUGH"),
        SKIP("SKIP"),
        UNESCAPE("UNESCAPE");

        private final String string;

        private InvalidEscapeStringPolicy(String string) {
            this.string = string;
        }

        public String getString() {
            return string;
        }
    }

    public interface PluginTask extends Task, TimestampParser.Task {
        @Config("stop_on_invalid_record")
        @ConfigDefault("false")
        boolean getStopOnInvalidRecord();

        @Config("invalid_string_escapes")
        @ConfigDefault("\"PASSTHROUGH\"")
        InvalidEscapeStringPolicy getInvalidEscapeStringPolicy();

        // TODO: Rename following experimental configs after they'er determined
        @Config("__experimental__json_pointer_to_root")
        @ConfigDefault("null")
        Optional<String> getJsonPointerToRoot();

        @Config("__experimental__columns")
        @ConfigDefault("null")
        Optional<SchemaConfig> getSchemaConfig();
    }

    @Override
    public void transaction(ConfigSource configSource, Control control) {
        PluginTask task = configSource.loadConfig(PluginTask.class);
        control.run(task.dump(), newSchema(task));
    }

    @VisibleForTesting
    Schema newSchema(PluginTask task) {
        if (isUseCustomSchema(task)) {
            return task.getSchemaConfig().get().toSchema();
        } else {
            return Schema.builder().add("record", Types.JSON).build(); // generate a schema
        }
    }

    @Override
    public void run(TaskSource taskSource, Schema schema, FileInput input, PageOutput output) {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        final boolean stopOnInvalidRecord = task.getStopOnInvalidRecord();
        final Map<Column, TimestampParser> timestampParsers = new HashMap<>();
        if (isUseCustomSchema(task)) {
            timestampParsers.putAll(Timestamps.newTimestampColumnParsersAsMap(task, task.getSchemaConfig().get()));
        }

        try (PageBuilder pageBuilder = newPageBuilder(schema, output);
                FileInputInputStream in = new FileInputInputStream(input)) {
            while (in.nextFile()) {
                final String fileName = input.hintOfCurrentInputFileNameForLogging().orElse("-");

                boolean evenOneJsonParsed = false;
                try (JsonParser.Stream stream = newJsonStream(in, task)) {
                    Value value;
                    while ((value = stream.next()) != null) {
                        try {
                            if (!value.isMapValue()) {
                                throw new JsonRecordValidateException(
                                        String.format("A Json record must not represent map value but it's %s", value.getValueType().name()));
                            }

                            if (isUseCustomSchema(task)) {
                                setValueWithCustomSchema(pageBuilder, schema, timestampParsers, value.asMapValue());
                            } else {
                                setValueWithSingleJsonColumn(pageBuilder, schema, value.asMapValue());
                            }
                            pageBuilder.addRecord();
                            evenOneJsonParsed = true;
                        } catch (JsonRecordValidateException e) {
                            if (stopOnInvalidRecord) {
                                throw new DataException(String.format("Invalid record in %s: %s", fileName, value.toJson()), e);
                            }
                            logger.warn(String.format("Skipped record in %s (%s): %s", fileName, e.getMessage(), value.toJson()));
                        }
                    }
                } catch (IOException | JsonParseException e) {
                    if (Exec.isPreview() && evenOneJsonParsed) {
                        // JsonParseException occurs when it cannot parse the last part of sampling buffer. Because
                        // the last part is sometimes invalid as JSON data. Therefore JsonParseException can be
                        // ignore in preview if at least one JSON is already parsed.
                        break;
                    }
                    throw new DataException(String.format("Failed to parse JSON: %s", fileName), e);
                }
            }

            pageBuilder.finish();
        }
    }

    private static boolean isUseCustomSchema(PluginTask task) {
        return task.getSchemaConfig().isPresent();
    }

    private static void setValueWithSingleJsonColumn(PageBuilder pageBuilder, Schema schema, MapValue value) {
        final Column column = schema.getColumn(0); // record column
        pageBuilder.setJson(column, value);
    }

    private static void setValueWithCustomSchema(
            PageBuilder pageBuilder, Schema schema, Map<Column, TimestampParser> timestampParsers, MapValue value) {
        final Map<Value, Value> map = value.map();
        for (Column column : schema.getColumns()) {
            final Value columnValue = map.get(ValueFactory.newString(column.getName()));
            if (columnValue == null || columnValue.isNilValue()) {
                pageBuilder.setNull(column);
                continue;
            }

            column.visit(new ColumnVisitor() {
                @Override
                public void booleanColumn(Column column) {
                    final boolean booleanValue;
                    if (columnValue.isBooleanValue()) {
                        booleanValue = columnValue.asBooleanValue().getBoolean();
                    } else {
                        booleanValue = Boolean.parseBoolean(columnValue.toString());
                    }
                    pageBuilder.setBoolean(column, booleanValue);
                }

                @Override
                public void longColumn(Column column) {
                    final long longValue;
                    if (columnValue.isIntegerValue()) {
                        longValue = columnValue.asIntegerValue().toLong();
                    } else {
                        longValue = Long.parseLong(columnValue.toString());
                    }
                    pageBuilder.setLong(column, longValue);
                }

                @Override
                public void doubleColumn(Column column) {
                    final double doubleValue;
                    if (columnValue.isFloatValue()) {
                        doubleValue = columnValue.asFloatValue().toDouble();
                    } else {
                        doubleValue = Double.parseDouble(columnValue.toString());
                    }
                    pageBuilder.setDouble(column, doubleValue);
                }

                @Override
                public void stringColumn(Column column) {
                    pageBuilder.setString(column, columnValue.toString());
                }

                @Override
                public void timestampColumn(Column column) {
                    final Timestamp timestampValue = timestampParsers.get(column).parse(columnValue.toString());
                    pageBuilder.setTimestamp(column, timestampValue);
                }

                @Override
                public void jsonColumn(Column column) {
                    pageBuilder.setJson(column, columnValue);
                }
            });
        }
    }

    private PageBuilder newPageBuilder(Schema schema, PageOutput output) {
        return new PageBuilder(Exec.getBufferAllocator(), schema, output);
    }

    private JsonParser.Stream newJsonStream(FileInputInputStream in, PluginTask task)
            throws IOException {
        final InvalidEscapeStringPolicy policy = task.getInvalidEscapeStringPolicy();
        final InputStream inputStream;
        switch (policy) {
            case SKIP:
            case UNESCAPE:
                byte[] lines = new BufferedReader(new InputStreamReader(in))
                        .lines()
                        .map(invalidEscapeStringFunction(policy))
                        .collect(Collectors.joining())
                        .getBytes(StandardCharsets.UTF_8);
                inputStream = new ByteArrayInputStream(lines);
                break;
            case PASSTHROUGH:
            default:
                inputStream = in;
        }

        if (task.getJsonPointerToRoot().isPresent()) {
            return jsonParser.openWithOffsetInJsonPointer(inputStream, task.getJsonPointerToRoot().get());
        } else {
            return jsonParser.open(inputStream);
        }
    }

    static Function<String, String> invalidEscapeStringFunction(final InvalidEscapeStringPolicy policy) {
        return input -> {
            Preconditions.checkNotNull(input);
            if (policy == InvalidEscapeStringPolicy.PASSTHROUGH) {
                return input;
            }
            StringBuilder builder = new StringBuilder();
            char[] charArray = input.toCharArray();
            for (int characterIndex = 0; characterIndex < charArray.length; characterIndex++) {
                char c = charArray[characterIndex];
                if (c == '\\') {
                    if (charArray.length > characterIndex + 1) {
                        char next = charArray[characterIndex + 1];
                        switch (next) {
                            case 'b':
                            case 'f':
                            case 'n':
                            case 'r':
                            case 't':
                            case '"':
                            case '\\':
                            case '/':
                                builder.append(c);
                                break;
                            case 'u': // hexstring such as \u0001
                                if (charArray.length > characterIndex + 5) {
                                    char[] hexChars = {charArray[characterIndex + 2], charArray[characterIndex + 3], charArray[characterIndex + 4],
                                                       charArray[characterIndex + 5]};
                                    String hexString = new String(hexChars);
                                    if (DIGITS_PATTERN.matcher(hexString).matches()) {
                                        builder.append(c);
                                    } else {
                                        if (policy == InvalidEscapeStringPolicy.SKIP) {
                                            // remove \\u
                                            characterIndex++;
                                        }
                                    }
                                }
                                break;
                            default:
                                switch (policy) {
                                    case SKIP:
                                        characterIndex++;
                                        break;
                                    case UNESCAPE:
                                        break;
                                    default:  // Do nothing, and just pass through.
                                }
                                break;
                        }
                    }
                } else {
                    builder.append(c);
                }
            }
            return builder.toString();
        };
    }

    static class JsonRecordValidateException extends DataException {
        JsonRecordValidateException(String message) {
            super(message);
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(JsonParserPlugin.class);

    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\p{XDigit}+");

    private final JsonParser jsonParser;
}
