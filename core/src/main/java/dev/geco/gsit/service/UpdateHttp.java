package dev.geco.gsit.service;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** One bounded HTTP request, with explicit status handling and no error-body logging. */
final class UpdateHttp {
    @FunctionalInterface interface ConnectionFactory { HttpURLConnection open(URI uri) throws IOException; }
    @FunctionalInterface interface Fetcher { String fetch(String source, String current, UpdateOptions options) throws Exception; }
    static final class Failure extends IOException {
        Failure(String reason) { super(reason); }
    }
    private final ConnectionFactory factory;
    UpdateHttp() { this(uri -> (HttpURLConnection)uri.toURL().openConnection()); }
    UpdateHttp(ConnectionFactory factory) { this.factory = factory; }
    String fetch(String source,String current,UpdateOptions options) throws IOException {
        String endpoint = switch (source) {
            case "github" -> "https://api.github.com/repos/gecolay/gsit/releases/latest";
            case "modrinth" -> "https://api.modrinth.com/v2/project/gsit/version";
            case "spigot" -> "https://api.spigotmc.org/legacy/update.php?resource=62325";
            case "paper" -> "https://hangar.papermc.io/api/v1/projects/gecolay/gsit/latest?channel=release";
            default -> throw new Failure("未知更新来源");
        };
        HttpURLConnection connection = factory.open(URI.create(endpoint));
        long deadline = System.nanoTime() + options.totalMs()*1_000_000L;
        try {
            connection.setConnectTimeout(Math.min(options.connectMs(), options.totalMs()));
            connection.setReadTimeout(Math.min(options.readMs(), options.totalMs()));
            connection.setRequestProperty("User-Agent", "GSit/"+current+" (+https://github.com/Gecolay/GSit)");
            connection.setRequestProperty("Accept", source.equals("github") ? "application/vnd.github+json" : "application/json, text/plain");
            if (source.equals("github")) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new Failure("HTTP "+status);
            if (connection.getContentLengthLong() > options.maxBytes()) throw new Failure("响应过大");
            byte[] bytes;
            try (InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int count;
                while (true) {
                    long remaining = deadline-System.nanoTime();
                    if (remaining<=0) throw new SocketTimeoutException();
                    connection.setReadTimeout((int)Math.max(1, Math.min(options.readMs(), (remaining+999999)/1_000_000)));
                    count=input.read(buffer);
                    if (count<0) break;
                    if (output.size()+count>options.maxBytes()) throw new Failure("响应过大");
                    output.write(buffer,0,count);
                }
                if (System.nanoTime()-deadline>=0) throw new SocketTimeoutException();
                bytes=output.toByteArray();
            }
            return parse(source,new String(bytes,StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
    static String parse(String source,String body) throws Failure {
        try {
            if (source.equals("spigot") || source.equals("paper")) return body.trim();
            JsonElement root=JsonParser.parseString(body);
            if (source.equals("modrinth")) {
                if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) throw new Failure("版本信息无效");
                root=root.getAsJsonArray().get(0);
            }
            if (!root.isJsonObject()) throw new Failure("版本信息无效");
            JsonElement tag=root.getAsJsonObject().get(source.equals("github") ? "tag_name" : "version_number");
            if (tag==null || !tag.isJsonPrimitive() || !tag.getAsJsonPrimitive().isString()) throw new Failure("版本信息无效");
            return tag.getAsString();
        } catch (JsonParseException | IllegalStateException error) { throw new Failure("版本信息无效"); }
    }
}
