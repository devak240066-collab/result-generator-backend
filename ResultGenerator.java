import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Multi-threaded Student Result Generator
 * 
 * Features:
 * - Parses a CSV file with columns: ID,Name,<Subject1>,<Subject2>,...
 * - Computes total, average, grade, pass/fail in parallel using a thread pool
 * - Ranks students by total (ties get the same rank)
 * - Prints a formatted table to the console and writes results to a CSV file
 * - Interactive mode to enter data manually when no CLI options are given
 *
 * Usage examples:
 *   javac ResultGenerator.java
 *   java ResultGenerator                // interactive mode
 *   java ResultGenerator --input students.csv
 *   java ResultGenerator --input students.csv --output results.csv --threads 4
 *
 * CSV format (header required):
 *   ID,Name,Math,Science,English,History,Geography
 *   S1,John Doe,85,78,92,66,81
 *   S2,Jane Smith,90,88,84,91,77
 */
public class ResultGenerator {

    private static final int FIXED_PASS_MARK = 40; // Pass mark fixed to 40

    // --------------------- Entry Point ---------------------
    public static void main(String[] args) {
        try {
            if (args == null || args.length == 0) {
                runInteractive();
                return;
            }

            Config config = Config.parse(args);
            if (config.showHelp) {
                printHelp();
                return;
            }

            DataSet dataSet;
            if (config.inputPath != null) {
                Path in = Paths.get(config.inputPath);
                if (Files.exists(in)) {
                    dataSet = DataLoader.fromCsv(in);
                    System.out.println("Loaded input from: " + in.toAbsolutePath());
                } else {
                    System.out.println("Input file not found: " + in.toAbsolutePath());
                    System.out.println("Falling back to interactive mode.\n");
                    runInteractive();
                    return;
                }
            } else {
                System.out.println("No input provided. Switching to interactive mode.\n");
                runInteractive();
                return;
            }

            int threads = config.threads != null ? config.threads : Math.max(2, Runtime.getRuntime().availableProcessors());
            int passMark = FIXED_PASS_MARK;

            System.out.println("Using threads: " + threads);
            System.out.println("Pass mark per subject: " + passMark);

            // Compute results concurrently
            List<StudentResult> results = computeResultsParallel(dataSet, threads, passMark);

            // Rank students
            rankResults(results);

            // Print results to console
            printTable(results, dataSet.subjects);
            printSummary(results);

            // Write CSV
            Path outPath = Paths.get(config.outputPath);
            DataLoader.writeResultsCsv(results, dataSet.subjects, outPath);
            System.out.println("\nResults written to: " + outPath.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runInteractive() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("Interactive mode: Enter student and subject details.");
        System.out.println();

        int passMark = FIXED_PASS_MARK; // fixed
        int threads = promptIntWithDefault(sc, "Enter number of worker threads (>0) [default CPU cores]: ", 1, Integer.MAX_VALUE,
                Math.max(2, Runtime.getRuntime().availableProcessors()));

        int subjectCount = promptInt(sc, "Enter number of subjects (>0): ", 1, 1000);
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < subjectCount; i++) {
            String s = promptNonEmpty(sc, "  Subject " + (i + 1) + " name: ");
            subjects.add(s);
        }

        int studentCount = promptInt(sc, "Enter number of students (>0): ", 1, 100000);
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < studentCount; i++) {
            System.out.println("Student " + (i + 1) + ":");
            String id = promptNonEmpty(sc, "  ID: ");
            String name = promptNonEmpty(sc, "  Name: ");
            Map<String, Integer> marks = new LinkedHashMap<>();
            for (String subj : subjects) {
                int mark = promptInt(sc, "    " + subj + " mark (0-100): ", 0, 100);
                marks.put(subj, mark);
            }
            students.add(new Student(id, name, marks));
        }

        DataSet dataSet = new DataSet(subjects, students);

        // Compute
        List<StudentResult> results = computeResultsParallel(dataSet, threads, passMark);
        rankResults(results);

        // Output
        System.out.println();
        System.out.println("Using threads: " + threads);
        System.out.println("Pass mark per subject: " + passMark);
        printTable(results, dataSet.subjects);
        printSummary(results);

        boolean write = promptYesNo(sc, "Write results to CSV? [Y/n]: ", true);
        if (write) {
            String defaultOut = "results.csv";
            String out = promptOptional(sc, "Output path [" + defaultOut + "]: ", defaultOut);
            try {
                DataLoader.writeResultsCsv(results, dataSet.subjects, Paths.get(out));
                System.out.println("\nResults written to: " + Paths.get(out).toAbsolutePath());
            } catch (IOException ioe) {
                System.err.println("Failed to write CSV: " + ioe.getMessage());
            }
        }
    }

    private static String promptNonEmpty(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine();
            if (s != null) {
                s = s.trim();
                if (!s.isEmpty()) return s;
            }
            System.out.println("  Value cannot be empty. Try again.");
        }
    }

    private static String promptOptional(Scanner sc, String prompt, String defaultVal) {
        System.out.print(prompt);
        String s = sc.nextLine();
        if (s == null) return defaultVal;
        s = s.trim();
        return s.isEmpty() ? defaultVal : s;
    }

    private static int promptInt(Scanner sc, String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine();
            try {
                int v = Integer.parseInt(s.trim());
                if (v < min || v > max) {
                    System.out.println("  Enter a value between " + min + " and " + max + ".");
                } else {
                    return v;
                }
            } catch (Exception e) {
                System.out.println("  Invalid number. Try again.");
            }
        }
    }

    private static int promptIntWithDefault(Scanner sc, String prompt, int min, int max, int defaultVal) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine();
            if (s == null || s.trim().isEmpty()) {
                return defaultVal;
            }
            try {
                int v = Integer.parseInt(s.trim());
                if (v < min || v > max) {
                    System.out.println("  Enter a value between " + min + " and " + max + ".");
                } else {
                    return v;
                }
            } catch (Exception e) {
                System.out.println("  Invalid number. Try again.");
            }
        }
    }

    private static boolean promptYesNo(Scanner sc, String prompt, boolean defaultYes) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine();
            if (s == null || s.trim().isEmpty()) return defaultYes;
            s = s.trim().toLowerCase(Locale.ROOT);
            if (s.equals("y") || s.equals("yes")) return true;
            if (s.equals("n") || s.equals("no")) return false;
            System.out.println("  Please answer with y/yes or n/no.");
        }
    }

    // --------------------- Core Logic ---------------------

    private static List<StudentResult> computeResultsParallel(DataSet dataSet, int threads, int passMark)
            throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<StudentResult>> tasks = new ArrayList<>();
            for (Student s : dataSet.students) {
                tasks.add(new ResultCalculator(s, dataSet.subjects, passMark));
            }
            List<Future<StudentResult>> futures = pool.invokeAll(tasks);
            List<StudentResult> results = new ArrayList<>(futures.size());
            for (Future<StudentResult> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private static void rankResults(List<StudentResult> results) {
        // Sort strictly by total descending (ties keep their relative order)
        results.sort(Comparator.comparingInt(StudentResult::getTotal).reversed());

        int rank = 0;
        int prevTotal = Integer.MIN_VALUE;
        int position = 0;
        for (StudentResult r : results) {
            position++;
            if (r.total != prevTotal) {
                rank = position;
                prevTotal = r.total;
            }
            r.rank = rank;
        }
    }

    // --------------------- Printing ---------------------

    private static void printTable(List<StudentResult> results, List<String> subjects) {
        if (results.isEmpty()) {
            System.out.println("No results to display.");
            return;
        }

        int idWidth = Math.max(2, Math.min(12, maxLen(results, r -> r.student.id)));
        int nameWidth = Math.max(4, Math.min(24, maxLen(results, r -> r.student.name)));
        List<Integer> subjWidths = new ArrayList<>();
        for (String subj : subjects) {
            subjWidths.add(Math.max(subj.length(), 5));
        }
        int totalWidth = Math.max(5, 5);
        int avgWidth = Math.max(5, 7);
        int gradeWidth = Math.max(5, 5);
        int statusWidth = Math.max(6, 6);
        int rankWidth = Math.max(4, 4);

        StringBuilder sb = new StringBuilder();
        // Header
        sb.append(pad("ID", idWidth)).append("  ")
          .append(pad("Name", nameWidth));
        for (int i = 0; i < subjects.size(); i++) {
            sb.append("  ").append(pad(subjects.get(i), subjWidths.get(i)));
        }
        sb.append("  ").append(pad("Total", totalWidth))
          .append("  ").append(pad("Average", avgWidth))
          .append("  ").append(pad("Grade", gradeWidth))
          .append("  ").append(pad("Status", statusWidth))
          .append("  ").append(pad("Rank", rankWidth))
          .append('\n');

        // Separator
        int totalLen = sb.lastIndexOf("\n");
        for (int i = 0; i < totalLen; i++) sb.append('-');
        sb.append('\n');

        DecimalFormat df = new DecimalFormat("0.00");
        for (StudentResult r : results) {
            sb.append(pad(r.student.id, idWidth)).append("  ")
              .append(pad(r.student.name, nameWidth));
            for (int i = 0; i < subjects.size(); i++) {
                String subj = subjects.get(i);
                int mark = r.student.marks.getOrDefault(subj, 0);
                sb.append("  ").append(pad(String.valueOf(mark), subjWidths.get(i)));
            }
            sb.append("  ").append(pad(String.valueOf(r.total), totalWidth))
              .append("  ").append(pad(df.format(r.average), avgWidth))
              .append("  ").append(pad(r.grade, gradeWidth))
              .append("  ").append(pad(r.pass ? "PASS" : "FAIL", statusWidth))
              .append("  ").append(pad(String.valueOf(r.rank), rankWidth))
              .append('\n');
        }

        System.out.println(sb.toString());
    }

    private static void printSummary(List<StudentResult> results) {
        long passCount = results.stream().filter(r -> r.pass).count();
        long failCount = results.size() - passCount;
        StudentResult top = results.stream().max(Comparator
                .comparingInt(StudentResult::getTotal)
                .thenComparingDouble(StudentResult::getAverage)).orElse(null);

        System.out.println("Summary:");
        System.out.println("  Total students: " + results.size());
        System.out.println("  Passed: " + passCount);
        System.out.println("  Failed: " + failCount);
        if (top != null) {
            System.out.println("  Topper: " + top.student.name + " (" + top.student.id + ") - Total: " + top.total + ", Average: " + String.format(Locale.US, "%.2f", top.average));
        }
    }

    private static int maxLen(List<StudentResult> results, java.util.function.Function<StudentResult, String> fn) {
        int max = 0;
        for (StudentResult r : results) {
            String s = fn.apply(r);
            if (s != null) max = Math.max(max, s.length());
        }
        return max;
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static void printHelp() {
        System.out.println("Multi-threaded Student Result Generator\n");
        System.out.println("Usage:");
        System.out.println("  javac ResultGenerator.java");
        System.out.println("  java ResultGenerator [options]\n");
        System.out.println("Options:");
        System.out.println("  -i, --input <path>     Input CSV file path (with header)");
        System.out.println("  -o, --output <path>    Output CSV file path (default: results.csv)");
        System.out.println("  -t, --threads <n>      Number of worker threads (default: CPU cores)");
        System.out.println("  -h, --help             Show this help message\n");
        System.out.println("CSV format (header required):");
        System.out.println("  ID,Name,Math,Science,English,History,Geography");
        System.out.println("  S1,John Doe,85,78,92,66,81");
        System.out.println("  S2,Jane Smith,90,88,84,91,77");
    }

    // --------------------- Data Models ---------------------

    static class Student {
        final String id;
        final String name;
        final Map<String, Integer> marks; // subject -> mark

        Student(String id, String name, Map<String, Integer> marks) {
            this.id = id;
            this.name = name;
            this.marks = Collections.unmodifiableMap(new LinkedHashMap<>(marks));
        }
    }

    static class StudentResult {
        final Student student;
        final int total;
        final double average;
        final boolean pass;
        final String grade;
        int rank;

        StudentResult(Student student, int total, double average, boolean pass, String grade) {
            this.student = student;
            this.total = total;
            this.average = average;
            this.pass = pass;
            this.grade = grade;
        }

        int getTotal() { return total; }
        double getAverage() { return average; }
    }

    static class DataSet {
        final List<String> subjects;
        final List<Student> students;

        DataSet(List<String> subjects, List<Student> students) {
            this.subjects = Collections.unmodifiableList(new ArrayList<>(subjects));
            this.students = Collections.unmodifiableList(new ArrayList<>(students));
        }
    }

    // --------------------- Calculator ---------------------

    static class ResultCalculator implements Callable<StudentResult> {
        private final Student student;
        private final List<String> subjects;
        private final int passMark;

        ResultCalculator(Student student, List<String> subjects, int passMark) {
            this.student = student;
            this.subjects = subjects;
            this.passMark = passMark;
        }

        @Override
        public StudentResult call() {
            int total = 0;
            boolean pass = true;
            for (String subj : subjects) {
                int m = student.marks.getOrDefault(subj, 0);
                total += m;
                if (m < passMark) pass = false;
            }
            double average = subjects.isEmpty() ? 0.0 : (double) total / subjects.size();
            String grade = determineGrade(average, pass);
            return new StudentResult(student, total, average, pass, grade);
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

    // --------------------- Data Loading & Writing ---------------------

    static class DataLoader {
        static DataSet fromCsv(Path path) throws IOException {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) throw new IOException("CSV is empty: " + path);

            List<String> header = parseCsvLine(lines.get(0));
            if (header.size() < 3) throw new IOException("CSV must have at least ID,Name and one subject column");

            if (!header.get(0).equalsIgnoreCase("ID") || !header.get(1).equalsIgnoreCase("Name")) {
                throw new IOException("First two columns must be ID,Name");
            }

            List<String> subjects = new ArrayList<>();
            for (int i = 2; i < header.size(); i++) {
                subjects.add(header.get(i).trim());
            }

            List<Student> students = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                List<String> cells = parseCsvLine(line);
                if (cells.size() < 2) continue;
                String id = cells.get(0).trim();
                String name = cells.get(1).trim();
                Map<String, Integer> marks = new LinkedHashMap<>();
                for (int c = 2; c < header.size(); c++) {
                    String subj = header.get(c).trim();
                    int mark = 0;
                    if (c < cells.size()) {
                        String v = cells.get(c).trim();
                        if (!v.isEmpty()) {
                            try {
                                mark = Integer.parseInt(v);
                            } catch (NumberFormatException e) {
                                warn("Invalid mark '" + v + "' for student " + id + ", subject " + subj + ". Treating as 0.");
                                mark = 0;
                            }
                        }
                    }
                    if (mark < 0 || mark > 100) {
                        warn("Out-of-range mark " + mark + " for student " + id + ", subject " + subj + ". Clamping to 0-100.");
                        mark = Math.max(0, Math.min(100, mark));
                    }
                    marks.put(subj, mark);
                }
                students.add(new Student(id, name, marks));
            }
            return new DataSet(subjects, students);
        }

        static void writeResultsCsv(List<StudentResult> results, List<String> subjects, Path outPath) throws IOException {
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
                // Header
                List<String> header = new ArrayList<>();
                header.add("ID");
                header.add("Name");
                header.addAll(subjects);
                header.addAll(Arrays.asList("Total", "Average", "Grade", "Status", "Rank"));
                w.write(joinCsv(header));
                w.newLine();

                DecimalFormat df = new DecimalFormat("0.00");
                for (StudentResult r : results) {
                    List<String> row = new ArrayList<>();
                    row.add(r.student.id);
                    row.add(r.student.name);
                    for (String subj : subjects) {
                        row.add(String.valueOf(r.student.marks.getOrDefault(subj, 0)));
                    }
                    row.add(String.valueOf(r.total));
                    row.add(df.format(r.average));
                    row.add(r.grade);
                    row.add(r.pass ? "PASS" : "FAIL");
                    row.add(String.valueOf(r.rank));
                    w.write(joinCsv(row));
                    w.newLine();
                }
                w.flush();
            }
        }

        private static void warn(String msg) {
            System.out.println("[WARN] " + msg);
        }
    }

    // --------------------- CSV Utilities ---------------------

    // Minimal CSV parser supporting commas and quotes
    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    cells.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    private static String joinCsv(List<String> cells) {
        StringJoiner sj = new StringJoiner(",");
        for (String c : cells) {
            if (c == null) c = "";
            boolean needQuotes = c.contains(",") || c.contains("\n") || c.contains("\r") || c.contains("\"");
            if (needQuotes) {
                String escaped = c.replace("\"", "\"\"");
                sj.add("\"" + escaped + "\"");
            } else {
                sj.add(c);
            }
        }
        return sj.toString();
    }

    // --------------------- Config & Args ---------------------

    static class Config {
        String inputPath = null;
        String outputPath = "results.csv";
        Integer threads = null;
        boolean showHelp = false;

        static Config parse(String[] args) {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        c.showHelp = true; break;
                    case "-i":
                    case "--input":
                        ensureValue(args, i, a); c.inputPath = args[++i]; break;
                    case "-o":
                    case "--output":
                        ensureValue(args, i, a); c.outputPath = args[++i]; break;
                    case "-t":
                    case "--threads":
                        ensureValue(args, i, a); c.threads = parsePositiveInt(args[++i], "threads"); break;
                    default:
                        System.out.println("Unknown option: " + a);
                        c.showHelp = true;
                        return c;
                }
            }
            return c;
        }

        private static void ensureValue(String[] args, int i, String opt) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for option " + opt);
        }
        private static Integer parsePositiveInt(String s, String name) {
            try {
                int v = Integer.parseInt(s);
                if (v <= 0) throw new IllegalArgumentException();
                return v;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid value for " + name + ": " + s);
            }
        }
    }
}
