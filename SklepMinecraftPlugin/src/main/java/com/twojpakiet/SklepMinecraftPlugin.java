package com.twojpakiet;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SklepMinecraftPlugin extends JavaPlugin {
    private HttpServer server;
    private String authToken;
    private String apiUrl;
    private int serverPort;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        getLogger().info("Plugin SklepMinecraftPlugin jest włączony.");

        // Start serwera HTTP
        try {
            server = HttpServer.create(new InetSocketAddress(serverPort), 0);
            server.createContext("/potwierdzenie_zakupu", new PurchaseConfirmationHandler());
            server.start();
            getLogger().info("Serwer HTTP wystartował na porcie " + serverPort + ".");
        } catch (IOException e) {
            getLogger().severe("Nie udało się uruchomić serwera HTTP.");
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
        getLogger().info("Plugin SklepMinecraftPlugin jest wyłączony.");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        this.apiUrl = config.getString("api.url");
        this.authToken = config.getString("api.token");
        this.serverPort = config.getInt("server.port", 8080);
    }

    private class PurchaseConfirmationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String[] data = requestBody.split("&");
                String username = null;
                String command = null;
                String status = null;
                String token = null;

                for (String param : data) {
                    String[] pair = param.split("=");
                    if (pair.length < 2) continue;
                    switch (pair[0]) {
                        case "username":
                            username = pair[1];
                            break;
                        case "command":
                            command = pair[1];
                            break;
                        case "status":
                            status = pair[1];
                            break;
                        case "token":
                            token = pair[1];
                            break;
                    }
                }

                if (authToken.equals(token) && "SUCCESS".equalsIgnoreCase(status) && username != null && command != null) {
                    String decodedCommand = java.net.URLDecoder.decode(command, StandardCharsets.UTF_8);
                    executeCommand(username, decodedCommand);

                    boolean apiResponse = sendPurchaseToAPI(username, decodedCommand);
                    if (apiResponse) {
                        sendResponse(exchange, "Potwierdzenie otrzymane. Komenda wykonana i dane wysłane do API.");
                    } else {
                        sendResponse(exchange, "Komenda wykonana, ale nie udało się połączyć z API.");
                    }
                } else {
                    sendResponse(exchange, "Błąd: Niepoprawne dane lub token.");
                }
            } else {
                sendResponse(exchange, "Metoda nieobsługiwana.");
            }
        }

        private void sendResponse(HttpExchange exchange, String response) throws IOException {
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }

    private void executeCommand(String username, String command) {
        new BukkitRunnable() {
            @Override
            public void run() {
                ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
                String formattedCommand = command.replace("@p", username);
                Bukkit.dispatchCommand(console, formattedCommand);
            }
        }.runTask(this);
    }

    private boolean sendPurchaseToAPI(String username, String command) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + authToken);
            connection.setDoOutput(true);

            String jsonPayload = String.format("{\"username\":\"%s\", \"command\":\"%s\"}", username, command);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                getLogger().info("Dane wysłane do API pomyślnie.");
                return true;
            } else {
                getLogger().warning("Nie udało się wysłać danych do API. Kod odpowiedzi: " + responseCode);
                return false;
            }
        } catch (IOException e) {
            getLogger().severe("Błąd podczas połączenia z API: " + e.getMessage());
            return false;
        }
    }
}
