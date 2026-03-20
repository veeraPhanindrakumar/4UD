import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HexFormat;

public class App {
    private static final int PORT = 3000;
    private static final Path ROOT = Paths.get("..", "frontend").toAbsolutePath().normalize();
    private static final Properties CONFIG = loadConfig();
    private static final String DB_URL = requiredConfig("db.url");
    private static final String DB_USER = requiredConfig("db.user");
    private static final String DB_PASSWORD = requiredConfig("db.password");
    private static final String HASH_PREFIX = "sha256:";

    public static void main(String[] args) throws Exception {
        initializeDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/", new ApiHandler());
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("Server running at http://localhost:" + PORT);
    }

    private static Properties loadConfig() {
        Properties properties = new Properties();
        Path configPath = Paths.get("config.properties");

        try (InputStream inputStream = Files.newInputStream(configPath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing backend/config.properties file.", exception);
        }

        return properties;
    }

    private static String requiredConfig(String key) {
        String value = CONFIG.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing config value: " + key);
        }
        return value.trim();
    }

    private static void initializeDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS contacts (
                        name VARCHAR(255) NOT NULL,
                        phone VARCHAR(10) NOT NULL,
                        email VARCHAR(255) NOT NULL,
                        address TEXT NOT NULL,
                        hidden_ind CHAR(1) NOT NULL DEFAULT 'N',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
                    )
                """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS admins (
                        admin_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(255) NOT NULL UNIQUE,
                        password VARCHAR(255) NOT NULL,
                        admin_ind CHAR(1) NOT NULL DEFAULT 'N'
                    )
                """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS students (
                        student_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(255) NOT NULL UNIQUE,
                        password VARCHAR(255) NOT NULL,
                        admin_ind CHAR(1) NOT NULL DEFAULT 'N'
                    )
                """);
            }

            migrateContactsTable(connection);
            ensureColumn(connection, connection.getMetaData(), "contacts", "hidden_ind", "ALTER TABLE contacts ADD COLUMN hidden_ind CHAR(1) NOT NULL DEFAULT 'N'");
            ensureColumn(connection, connection.getMetaData(), "admins", "admin_ind", "ALTER TABLE admins ADD COLUMN admin_ind CHAR(1) NOT NULL DEFAULT 'N'");
            ensureColumn(connection, connection.getMetaData(), "students", "admin_ind", "ALTER TABLE students ADD COLUMN admin_ind CHAR(1) NOT NULL DEFAULT 'N'");
            migrateAccountPasswords(connection, "admins");
            migrateAccountPasswords(connection, "students");
        }
    }

    private static void migrateContactsTable(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();

        if (hasColumn(metaData, "contacts", "id")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE contacts DROP COLUMN id");
            }
        }

        ensureUniqueIndex(connection, metaData, "contacts", "uk_contacts_name", "name");
        ensureUniqueIndex(connection, metaData, "contacts", "uk_contacts_phone", "phone");
    }

    private static boolean hasColumn(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        try (ResultSet columns = metaData.getColumns(null, null, tableName, columnName)) {
            return columns.next();
        }
    }

    private static boolean hasIndex(DatabaseMetaData metaData, String tableName, String indexName) throws SQLException {
        try (ResultSet indexes = metaData.getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                String currentName = indexes.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(currentName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void ensureUniqueIndex(
        Connection connection,
        DatabaseMetaData metaData,
        String tableName,
        String indexName,
        String columnName
    ) throws SQLException {
        if (hasIndex(metaData, tableName, indexName)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD CONSTRAINT " + indexName + " UNIQUE (" + columnName + ")");
        }
    }

    private static void ensureColumn(
        Connection connection,
        DatabaseMetaData metaData,
        String tableName,
        String columnName,
        String alterSql
    ) throws SQLException {
        if (hasColumn(metaData, tableName, columnName)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(alterSql);
        }
    }

    private static void migrateAccountPasswords(Connection connection, String tableName) throws SQLException {
        List<String[]> updates = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT username, password FROM " + tableName
        );
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String username = resultSet.getString("username");
                String password = resultSet.getString("password");
                if (password != null && !password.startsWith(HASH_PREFIX)) {
                    updates.add(new String[]{username, hashPassword(password)});
                }
            }
        }

        if (updates.isEmpty()) {
            return;
        }

        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE " + tableName + " SET password = ? WHERE username = ?"
        )) {
            for (String[] item : updates) {
                update.setString(1, item[1]);
                update.setString(2, item[0]);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if ("/api/contact".equals(path)) {
                handleContactSubmission(exchange);
                return;
            }

            if ("/api/login".equals(path)) {
                handleLogin(exchange);
                return;
            }

            if ("/api/admin/contacts".equals(path)) {
                handleContactsList(exchange);
                return;
            }

            if ("/api/admin/contacts/hide".equals(path)) {
                hideContacts(exchange);
                return;
            }

            writeJson(exchange, 404, "{\"message\":\"Not found\"}");
        }

        private void handleContactSubmission(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
                return;
            }

            try (InputStream inputStream = exchange.getRequestBody()) {
                String body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> fields = parseJsonBody(body);

                String name = value(fields.get("name"));
                String phone = digitsOnly(value(fields.get("phone")));
                String email = value(fields.get("email"));
                String address = value(fields.get("address"));

                if (name.isBlank() || email.isBlank() || address.isBlank() || phone.length() != 10 || !isValidEmail(email)) {
                    writeJson(exchange, 400, "{\"message\":\"Please enter name, email, address, and a valid 10-digit phone number.\"}");
                    return;
                }

                saveContact(name, phone, email, address);
                writeJson(exchange, 200, "{\"message\":\"Thanks " + escapeJson(name) + ", we will reach out shortly.\"}");
            } catch (SQLIntegrityConstraintViolationException exception) {
                writeJson(exchange, 409, "{\"message\":\"Name and phone number must be unique.\"}");
            } catch (Exception exception) {
                writeJson(exchange, 500, "{\"message\":\"Unable to save contact details.\"}");
            }
        }

        private void handleContactsList(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
                return;
            }

            try {
                writeJson(exchange, 200, buildContactsJson());
            } catch (Exception exception) {
                writeJson(exchange, 500, "{\"message\":\"Unable to load contacts.\"}");
            }
        }

        private void hideContacts(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
                return;
            }

            try (InputStream inputStream = exchange.getRequestBody()) {
                String body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                List<Map<String, String>> contacts = parseContactsPayload(body);

                if (contacts.isEmpty()) {
                    writeJson(exchange, 400, "{\"message\":\"Select at least one contact.\"}");
                    return;
                }

                int updated = hideSelectedContacts(contacts);
                writeJson(exchange, 200, "{\"message\":\"" + updated + " contact(s) hidden successfully.\"}");
            } catch (Exception exception) {
                writeJson(exchange, 500, "{\"message\":\"Unable to hide selected contacts.\"}");
            }
        }

        private void handleLogin(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
                return;
            }

            try (InputStream inputStream = exchange.getRequestBody()) {
                String body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> fields = parseJsonBody(body);

                String role = value(fields.get("role")).toLowerCase();
                String username = value(fields.get("username"));
                String password = value(fields.get("password"));

                if ((!role.equals("admin") && !role.equals("student")) || username.isBlank() || password.isBlank()) {
                    writeJson(exchange, 400, "{\"message\":\"Please enter role, username, and password.\"}");
                    return;
                }

                if (!isValidLogin(role, username, password)) {
                    writeJson(exchange, 401, "{\"message\":\"Invalid username or password.\"}");
                    return;
                }

                String redirect = role.equals("admin") ? "admin/dashboard.html" : "student/home.html";
                writeJson(exchange, 200, "{\"message\":\"Login successful.\",\"redirect\":\"" + redirect + "\"}");
            } catch (Exception exception) {
                writeJson(exchange, 500, "{\"message\":\"Unable to complete login.\"}");
            }
        }

        private void saveContact(String name, String phone, String email, String address) throws SQLException {
            try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO contacts (name, phone, email, address) VALUES (?, ?, ?, ?)"
                 )) {
                statement.setString(1, name);
                statement.setString(2, phone);
                statement.setString(3, email);
                statement.setString(4, address);
                statement.executeUpdate();
            }
        }

        private String buildContactsJson() throws SQLException {
            List<String> rows = new ArrayList<>();

            try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT name, phone, email, address, created_at FROM contacts WHERE hidden_ind = 'N' ORDER BY created_at DESC"
                 );
                 ResultSet resultSet = statement.executeQuery()) {

                while (resultSet.next()) {
                    rows.add(
                        "{"
                            + "\"name\":\"" + escapeJson(resultSet.getString("name")) + "\","
                            + "\"phone\":\"" + escapeJson(resultSet.getString("phone")) + "\","
                            + "\"email\":\"" + escapeJson(resultSet.getString("email")) + "\","
                            + "\"address\":\"" + escapeJson(resultSet.getString("address")) + "\","
                            + "\"createdAt\":\"" + escapeJson(String.valueOf(resultSet.getTimestamp("created_at"))) + "\""
                            + "}"
                    );
                }
            }

            return "{\"contacts\":[" + String.join(",", rows) + "]}";
        }

        private int hideSelectedContacts(List<Map<String, String>> contacts) throws SQLException {
            int updated = 0;

            try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE contacts SET hidden_ind = 'Y' WHERE name = ? AND phone = ?"
                 )) {
                for (Map<String, String> contact : contacts) {
                    statement.setString(1, value(contact.get("name")));
                    statement.setString(2, digitsOnly(value(contact.get("phone"))));
                    updated += statement.executeUpdate();
                }
            }

            return updated;
        }

        private boolean isValidLogin(String role, String username, String password) throws SQLException {
            String hashedPassword = hashPassword(password);
            String sql = role.equals("admin")
                ? "SELECT 1 FROM admins WHERE username = ? AND password = ? AND admin_ind = 'Y'"
                : "SELECT 1 FROM students WHERE username = ? AND password = ?";

            try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setString(2, hashedPassword);

                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        }
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String requestPath = exchange.getRequestURI().getPath();
            String relativePath = "/".equals(requestPath) ? "index.html" : requestPath.substring(1);
            Path resolvedPath = ROOT.resolve(relativePath).normalize();

            if (!resolvedPath.startsWith(ROOT) || Files.isDirectory(resolvedPath) || !Files.exists(resolvedPath)) {
                byte[] content = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, content.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(content);
                }
                return;
            }

            byte[] content = Files.readAllBytes(resolvedPath);
            String mimeType = URLConnection.guessContentTypeFromName(resolvedPath.getFileName().toString());
            exchange.getResponseHeaders().set("Content-Type", mimeType != null ? mimeType : "application/octet-stream");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(content);
            }
        }
    }

    private static Map<String, String> parseJsonBody(String body) {
        Map<String, String> values = new HashMap<>();
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("}")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return values;
        }

        String[] pairs = trimmed.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] parts = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = stripQuotes(parts[0].trim());
            String value = stripQuotes(parts[1].trim());
            values.put(key, unescapeJson(value));
        }
        return values;
    }

    private static List<Map<String, String>> parseContactsPayload(String body) {
        List<Map<String, String>> contacts = new ArrayList<>();
        String compact = body.replace("\r", "").replace("\n", "").trim();
        int keyIndex = compact.indexOf("\"contacts\"");
        if (keyIndex < 0) {
            return contacts;
        }

        int arrayStart = compact.indexOf('[', keyIndex);
        int arrayEnd = compact.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd <= arrayStart) {
            return contacts;
        }

        String arrayContent = compact.substring(arrayStart + 1, arrayEnd).trim();
        if (arrayContent.isBlank()) {
            return contacts;
        }

        String[] entries = arrayContent.split("\\},\\s*\\{");
        for (String entry : entries) {
            String normalized = entry;
            if (!normalized.startsWith("{")) {
              normalized = "{" + normalized;
            }
            if (!normalized.endsWith("}")) {
              normalized = normalized + "}";
            }
            contacts.add(parseJsonBody(normalized));
        }

        return contacts;
    }

    private static String stripQuotes(String value) {
        String result = value;
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static String unescapeJson(String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\");
    }

    private static String digitsOnly(String value) {
        return value.replaceAll("\\D", "");
    }

    private static String value(String input) {
        return input == null ? "" : input.trim();
    }

    private static boolean isValidEmail(String email) {
        return email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + HexFormat.of().formatHex(hashedBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 hashing is unavailable.", exception);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(content);
        }
    }
}
