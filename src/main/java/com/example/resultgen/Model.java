package com.example.resultgen;

import java.io.BufferedReader;
import java.io.StringReader;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Callable;

public class Model {
    // This file contains model classes for the web app. Kept minimal and self-contained.
}

class Student {
    final String id;
    final String name;
    final Map<String, Integer> marks;

    Student(String id, String name, Map<String, Integer> marks) {
        this.id = id; this.name = name; this.marks = Collections.unmodifiableMap(new LinkedHashMap<>(marks));
    }
}

class StudentResult {
    final Student student;
    final int total;
    final double average;
    final boolean pass;
    final String grade;
    int rank;

    StudentResult(Student student, int total, double average, boolean pass, String grade) {
        this.student = student; this.total = total; this.average = average; this.pass = pass; this.grade = grade;
    }
    int getTotal() { return total; }
    double getAverage() { return average; }
}

class DataSet {
    final List<String> subjects;
    final List<Student> students;

    DataSet(List<String> subjects, List<Student> students) {
        this.subjects = Collections.unmodifiableList(new ArrayList<>(subjects));
        this.students = Collections.unmodifiableList(new ArrayList<>(students));
    }
}

class ResultCalculator implements Callable<StudentResult> {
    private final Student student; private final List<String> subjects; private final int passMark;
    ResultCalculator(Student student, List<String> subjects, int passMark) { this.student = student; this.subjects = subjects; this.passMark = passMark; }
    @Override public StudentResult call() {
        int total = 0; boolean pass = true;
        for (String subj : subjects) {
            int m = student.marks.getOrDefault(subj, 0);
            total += m; if (m < passMark) pass = false;
        }
        double avg = subjects.isEmpty() ? 0.0 : (double) total / subjects.size();
        String grade = determineGrade(avg, pass);
        return new StudentResult(student, total, avg, pass, grade);
    }
    private String determineGrade(double avg, boolean pass) {
        if (!pass) return "F";
        if (avg >= 90) return "A+";
        if (avg >= 80) return "A";
        if (avg >= 70) return "B+";
        if (avg >= 60) return "B";
        if (avg >= 50) return "C";
        if (avg >= 40) return "D";
        return "F";
    }
}

class DataLoader {
    static DataSet fromCsvString(String csv) throws Exception {
        BufferedReader br = new BufferedReader(new StringReader(csv));
        String headerLine = br.readLine();
        if (headerLine == null) throw new Exception("CSV is empty");
        List<String> header = parseCsvLine(headerLine);
        if (header.size() < 3) throw new Exception("CSV must have at least ID,Name and one subject column");
        if (!header.get(0).equalsIgnoreCase("ID") || !header.get(1).equalsIgnoreCase("Name")) throw new Exception("First two columns must be ID,Name");
        List<String> subjects = new ArrayList<>();
        for (int i = 2; i < header.size(); i++) subjects.add(header.get(i).trim());

        List<Student> students = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim(); if (line.isEmpty()) continue;
            List<String> cells = parseCsvLine(line);
            if (cells.size() < 2) continue;
            String id = cells.get(0).trim(); String name = cells.get(1).trim();
            Map<String, Integer> marks = new LinkedHashMap<>();
            for (int c = 2; c < header.size(); c++) {
                String subj = header.get(c).trim(); int mark = 0;
                if (c < cells.size()) { String v = cells.get(c).trim(); if (!v.isEmpty()) { try { mark = Integer.parseInt(v); } catch (NumberFormatException e) { mark = 0; } } }
                if (mark < 0 || mark > 100) mark = Math.max(0, Math.min(100, mark));
                marks.put(subj, mark);
            }
            students.add(new Student(id, name, marks));
        }
        return new DataSet(subjects, students);
    }

    // Minimal CSV parser copied from original project
    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else { inQuotes = false; }
                } else { cur.append(ch); }
            } else {
                if (ch == '"') inQuotes = true;
                else if (ch == ',') { cells.add(cur.toString()); cur.setLength(0); }
                else cur.append(ch);
            }
        }
        cells.add(cur.toString()); return cells;
    }

    static String joinCsv(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String c : cells) {
            if (!first) sb.append(','); first = false;
            if (c == null) c = "";
            boolean needQuotes = c.contains(",") || c.contains("\n") || c.contains("\r") || c.contains("\"");
            if (needQuotes) { String escaped = c.replace("\"", "\"\""); sb.append('"').append(escaped).append('"'); }
            else sb.append(c);
        }
        return sb.toString();
    }
}
