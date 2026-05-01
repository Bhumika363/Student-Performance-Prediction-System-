import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;

public class PredictionServer {
    private static Classifier model;
    private static Instances dataset;
    private static ArrayList<Attribute> attributes;

    // --- DATABASE CONFIGURATION ---
    // Updated to match your 'prediction' database
    private static final String DB_URL = "jdbc:mysql://localhost:3306/prediction"; 
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "Your_Password"; 

    public static void main(String[] args) throws Exception {
        // 1. Load the WEKA model from your model folder
        String modelPath = "model/final_prediction.model";
        model = (Classifier) weka.core.SerializationHelper.read(modelPath);

        // 2. Define attributes to match your ARFF file
        attributes = new ArrayList<>();
        attributes.add(new Attribute("Attendance %"));
        attributes.add(new Attribute("mst1"));
        attributes.add(new Attribute("mst2"));
        attributes.add(new Attribute("final"));

        dataset = new Instances("TestDataset", attributes, 0);
        dataset.setClassIndex(dataset.numAttributes() - 1);

        // 3. Start a lightweight local server on port 8000
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/api/predict", new PredictHandler());
        server.setExecutor(null); 
        server.start();
        
        System.out.println("=========================================");
        System.out.println("Server started on http://localhost:8000");
        System.out.println("Model loaded successfully. Waiting for UI...");
        System.out.println("=========================================");
    }

    // This handles the incoming web requests
    static class PredictHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Allow your HTML file to bypass CORS restrictions
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            // Handle pre-flight checks
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    // Read the JSON data sent from the HTML page
                    InputStream is = exchange.getRequestBody();
                    String body = new String(is.readAllBytes());

                    // Extract numbers manually 
                    double attendance = extractValue(body, "attendance");
                    double mst1 = extractValue(body, "mst1");
                    double mst2 = extractValue(body, "mst2");
                    
                    // Extract text manually
                    String studentName = extractStringValue(body, "studentName");
                    String studentCourse = extractStringValue(body, "studentcourse");

                    // Create the instance for WEKA
                    DenseInstance student = new DenseInstance(4);
                    student.setDataset(dataset);
                    student.setValue(attributes.get(0), attendance);
                    student.setValue(attributes.get(1), mst1);
                    student.setValue(attributes.get(2), mst2);

                    // Predict!
                    double predictedScore = model.classifyInstance(student);
                    long roundedScore = Math.round(predictedScore);
                    String finalGrade = convertToGrade(roundedScore);

                    // Format the response back to the website
                    String response = String.format("{\"score\": %.2f, \"grade\": \"%s\"}", predictedScore, finalGrade);

                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();

                    System.out.println("\n--- New Prediction ---");
                    System.out.println("Student: " + studentName + " (" + studentCourse + ")");
                    System.out.println("Predicted: " + finalGrade + " (" + predictedScore + "/50)");

                    // ==========================================
                    // 4. SAVE TO DATABASE
                    // ==========================================
                    try {
                        Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                        
                        // Updated to match your exact SQL table and column names
                        String sql = "INSERT INTO predictions_table (student_name, course, attendance, mst1, mst2, predicted_score, predicted_grade) VALUES (?, ?, ?, ?, ?, ?, ?)";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        
                        pstmt.setString(1, studentName);
                        pstmt.setString(2, studentCourse);
                        pstmt.setDouble(3, attendance);
                        pstmt.setDouble(4, mst1);
                        pstmt.setDouble(5, mst2);
                        pstmt.setDouble(6, predictedScore);
                        pstmt.setString(7, finalGrade);
                        
                        pstmt.executeUpdate();
                        pstmt.close();
                        conn.close();
                        
                        System.out.println("[SUCCESS] Saved to MySQL database 'prediction'.");
                    } catch (Exception dbErr) {
                        System.err.println("[ERROR] Failed to save to DB: " + dbErr.getMessage());
                        System.err.println("TIP: Do you have the mysql-connector-java.jar in your lib folder?");
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    exchange.sendResponseHeaders(500, -1);
                }
            }
        }

        // Simple helper to parse numeric values from JSON
        private double extractValue(String json, String key) {
            try {
                String search = "\"" + key + "\":";
                int index = json.indexOf(search);
                if (index == -1) return 0.0;
                int start = index + search.length();
                int end = json.indexOf(",", start);
                if (end == -1) end = json.indexOf("}", start);
                return Double.parseDouble(json.substring(start, end).trim());
            } catch (Exception e) { return 0.0; }
        }

        // Simple helper to parse String text from JSON
        private String extractStringValue(String json, String key) {
            try {
                String search = "\"" + key + "\":\"";
                int index = json.indexOf(search);
                if (index == -1) return "Unknown";
                int start = index + search.length();
                int end = json.indexOf("\"", start);
                return json.substring(start, end);
            } catch (Exception e) { return "Unknown"; }
        }
    }

    // Your existing grading logic
    public static String convertToGrade(long score) {
        if (score >= 45) return "A+";
        else if (score >= 40) return "A";
        else if (score >= 35) return "B+";
        else if (score >= 30) return "B";
        else if (score >= 25) return "C";
        else if (score >= 20) return "D";
        else return "F"; 
    }
}