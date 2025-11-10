package com.example.resultgen;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Data persistence helper for saving student results.
 * Supports JSON (for logs/archives) and XLSX (for Excel).
 */
public class DB {
    private static final String DATA_DIR = "data";
    
    /**
     * Save results to a JSON file. Returns the saved absolute path.
     */
    public static String saveResults(List<StudentResult> results, List<String> subjects) throws IOException {
        Path dataDir = Paths.get(DATA_DIR);
        if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String filename = "results_" + sdf.format(new Date()) + ".json";
        Path filePath = dataDir.resolve(filename);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"subjects\": [");
        for (int i = 0; i < subjects.size(); i++) {
            if (i > 0) json.append(", ");
            json.append("\"").append(escapeJson(subjects.get(i))).append("\"");
        }
        json.append("],\n");
        json.append("  \"totalStudents\": ").append(results.size()).append(",\n");
        json.append("  \"savedAt\": \"").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\",\n");
        json.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            StudentResult sr = results.get(i);
            json.append("    {\n");
            json.append("      \"id\": \"").append(escapeJson(sr.student.id)).append("\",\n");
            json.append("      \"name\": \"").append(escapeJson(sr.student.name)).append("\",\n");
            json.append("      \"marks\": {\n");
            int markIdx = 0;
            for (String subj : subjects) {
                if (markIdx > 0) json.append(",\n");
                json.append("        \"").append(escapeJson(subj)).append("\": ").append(sr.student.marks.getOrDefault(subj, 0));
                markIdx++;
            }
            json.append("\n      },\n");
            json.append("      \"total\": ").append(sr.total).append(",\n");
            json.append("      \"average\": ").append(String.format(\"%.2f\", sr.average)).append(",\n");
            json.append("      \"grade\": \"").append(escapeJson(sr.grade)).append("\",\n");
            json.append("      \"status\": \"").append(sr.pass ? \"PASS\" : \"FAIL\").append("\",\n");
            json.append("      \"rank\": ").append(sr.rank).append("\n");
            json.append("    }");
            if (i < results.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}");

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            writer.write(json.toString());
        }
        return filePath.toAbsolutePath().toString();
    }

    /**
     * Save results to an Excel file (XLSX) under data/ (default filename result.xlsx).
     * Returns the saved absolute path.
     */
    public static String saveExcel(List<StudentResult> results, List<String> subjects, String fileName) throws IOException {
        Path dataDir = Paths.get(DATA_DIR);
        if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
        if (fileName == null || fileName.trim().isEmpty()) fileName = "result.xlsx";
        if (!fileName.toLowerCase().endsWith(".xlsx")) fileName = fileName + ".xlsx";
        Path out = dataDir.resolve(fileName);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Student Results");
            Row header = sheet.createRow(0);
            int c = 0;
            header.createCell(c++).setCellValue("ID");
            header.createCell(c++).setCellValue("Name");
            for (String s : subjects) header.createCell(c++).setCellValue(s);
            header.createCell(c++).setCellValue("Total");
            header.createCell(c++).setCellValue("Average");
            header.createCell(c++).setCellValue("Grade");
            header.createCell(c++).setCellValue("Status");
            header.createCell(c++).setCellValue("Rank");

            int r = 1;
            for (StudentResult sr : results) {
                Row row = sheet.createRow(r++);
                c = 0;
                row.createCell(c++).setCellValue(sr.student.id == null ? "" : sr.student.id);
                row.createCell(c++).setCellValue(sr.student.name == null ? "" : sr.student.name);
                for (String subj : subjects) row.createCell(c++).setCellValue(sr.student.marks.getOrDefault(subj, 0));
                row.createCell(c++).setCellValue(sr.total);
                row.createCell(c++).setCellValue(sr.average);
                row.createCell(c++).setCellValue(sr.grade == null ? "" : sr.grade);
                row.createCell(c++).setCellValue(sr.pass ? "PASS" : "FAIL");
                row.createCell(c++).setCellValue(sr.rank);
            }

            for (int i = 0; i < header.getLastCellNum(); i++) sheet.autoSizeColumn(i);
            try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
                wb.write(fos);
            }
        }
        return out.toAbsolutePath().toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
