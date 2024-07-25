///usr/bin/env jbang "$0" "$@" ; exit $? # (1)
//DEPS io.vertx:vertx-core:3.9.4
//DEPS info.picocli:picocli:4.5.0


import java.io.*;
import java.util.*;
import java.util.stream.*;
import java.nio.charset.*;
import java.nio.file.*;

import io.vertx.core.json.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

import com.fasterxml.jackson.annotation.*;

@Command(name = "compatibility", mixinStandardHelpOptions = true, version = "0.1",
        description = "Compatibility Utils")
class CompatibilityUtils implements Callable<Integer> {

    @Parameters(index = "0", description = "Command among 'extract' and 'clear'")
    private String command;

    public static void main(String... args) {
        int exitCode = new CommandLine(new CompatibilityUtils()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        List<File> files = getRevapiConfigFiles();
        if (command.equalsIgnoreCase("extract")) {
            List<Difference> differences = new ArrayList<>();
            for (File f : files) {
                System.out.println("\uD83D\uDD0E Found revapi.json file: " + f.getAbsolutePath());
                JsonArray json = new JsonArray(read(f));
                List<Difference> diff = extractDifferences(json);
                differences.addAll(diff);

            }
            System.out.println("\uD83E\uDDEE " + differences.size() + " difference(s) found");
            if (! differences.isEmpty()) {
                writeDifferences(differences, new File("target/differences.md"));
            }
            return 0;
        }

        if (command.equalsIgnoreCase("clear")) {
            for (File f : files) {
                System.out.println("\uD83D\uDD0E Clearing differences from revapi.json file: " + f.getAbsolutePath());
                JsonArray json = new JsonArray(read(f));
                clearDifferences(f, json);
            }
            return 0;
        }


        return 1;
    }

    private void writeDifferences(List<Difference> diff, File file) {
        if (file.isFile()) {
            file.delete();
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("### Breaking Changes\n\n");

        for (Difference difference : diff) {
            buffer.append("  * ").append(difference.toString()).append("\n");
        }
        buffer.append("\n");

        try {
            Files.writeString(file.toPath(), buffer.toString());
            System.out.println("\uD83D\uDCBD File " + file.getAbsolutePath() + " written");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<File> getRevapiConfigFiles() {
        List<File> matches = new ArrayList<>();
        File root = new File(System.getProperty("user.dir"));
        File[] files = root.listFiles();
        if (files == null) {
            throw new IllegalStateException("Unable to read the file system");
        } else {
            for (File f : files) {
                if (f.isDirectory()) {
                    File maybe = new File(f, "revapi.json");
                    if (maybe.isFile()) {
                        matches.add(maybe);
                    }
                }
            }
        }
        return matches;
    }

    static List<Difference> extractDifferences(JsonArray array) {
        Optional<JsonArray> diff = array.stream()
                .map(obj -> (JsonObject) obj)
                .filter(json -> json.getString("extension").equals("revapi.differences"))
                .map(json -> json.getJsonObject("configuration"))
                .map(conf -> conf.getJsonArray("differences"))
                .findFirst();

        return diff
                .map(objects ->
                        objects.stream().map(obj -> (JsonObject) obj).map(json -> json.mapTo(Difference.class)).collect(Collectors.toList())
                )
                .orElse(Collections.emptyList());
    }

    static void clearDifferences(File file, JsonArray array) {
        boolean updated = false;
        for (Object obj : array) {
            JsonObject json = (JsonObject) obj;
            if ("revapi.differences".equalsIgnoreCase(json.getString("extension"))) {
                JsonObject configuration = json.getJsonObject("configuration");
                JsonArray differences = configuration.getJsonArray("differences");
                if (differences != null  && ! differences.isEmpty()) {
                    updated = true;
                    configuration.remove("differences");
                    configuration.put("differences", new JsonArray());
                }
            }
        }

        if (updated) {
            System.out.println("\uD83D\uDCC1 Updating " + file.getAbsolutePath());
            try {
                Files.writeString(file.toPath(), array.encodePrettily());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

    }

    static String read(File file) {
        StringBuilder content = new StringBuilder();
        try (Stream<String> stream = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            stream.forEach(s -> content.append(s).append("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return content.toString();
    }

    static boolean isNotBlank(String s) {
        if (s == null) {
            return false;
        }
        return !s.trim().isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Difference {
        public String code;
        @JsonProperty("old")
        public String oldMethod;
        @JsonProperty("new")
        public String newMethod;
        public String justification;

        @java.lang.Override
        public java.lang.String toString() {
            if (isNotBlank(oldMethod)  && isNotBlank(newMethod)) {
                return "`" + oldMethod + "` updated to `" + newMethod + "`: _" + justification + "_";
            }
            if (isNotBlank(oldMethod)) {
                return "`" + oldMethod + "` has been removed: _" + justification  + "_";
            }
            if (isNotBlank(newMethod)) {
                return "`" + newMethod + "` has been introduced: _" + justification  + "_";
            }

            return  code + " " + justification;
        }
    }

}
