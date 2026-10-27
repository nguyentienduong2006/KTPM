import com.github.javafaker.Faker;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DatabaseBenchmark {

    // kết nối docker
    private static final String URL = "jdbc:postgresql://localhost:5432/testdb";
    private static final String USER = "admin";
    private static final String PASSWORD = "adminpassword";

    public static void main(String[] args) {
        Faker faker = new Faker(Locale.forLanguageTag("vi"));

        // Dữ liệu mẫu
        String[] wards = {"Cầu Giấy", "Dịch Vọng", "Quan Hoa", "Nghĩa Đô", "Trung Hòa", "Mỹ Đình", "Bến Thành"};
        String[] provinces = {"Hà Nội", "TP. Hồ Chí Minh", "Đà Nẵng", "Hải Phòng", "Cần Thơ"};

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            System.out.println("Kết nối Database thành công!");

            // 1. Khởi tạo bảng
            initTable(conn);

            generateData(conn, faker, wards, provinces, 10000);

            System.out.println("\n--- BASELINE QUERY (Dùng vòng lặp for) ---");
            long startBaseline = System.currentTimeMillis();

            // lọc
            List<User> allUsers = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
                while (rs.next()) {
                    allUsers.add(new User(
                            rs.getInt("id"),
                            rs.getString("full_name"),
                            rs.getString("detail_address"),
                            rs.getString("ward"),
                            rs.getString("province")
                    ));
                }
            }

            List<User> filteredUsers = new ArrayList<>();
            for (User user : allUsers) {
                if ("Cầu Giấy".equals(user.ward)) {
                    filteredUsers.add(user);
                }
            }

            long endBaseline = System.currentTimeMillis();
            System.out.println("Số lượng user ở Cầu Giấy: " + filteredUsers.size());
            System.out.printf("Thời gian Baseline (for loop): %,d ms\n", (endBaseline - startBaseline));

            System.out.println("\n--- TẠO INDEX VÀ QUERY TỐI ƯU ---");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ward ON users(ward)");
                System.out.println("Đã tạo index trên cột 'ward'.");
            }

            long startIndexed = System.currentTimeMillis();
            int count = 0;
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM users WHERE ward = ?")) {
                pstmt.setString(1, "Cầu Giấy");
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        count++;
                    }
                }
            }
            long endIndexed = System.currentTimeMillis();

            System.out.println("Số lượng user ở Cầu Giấy: " + count);
            System.out.printf("Thời gian có Index (SQL WHERE): %,d ms\n", (endIndexed - startIndexed));

            // Đánh giá
            System.out.println("\n--- ĐÁNH GIÁ ---");
            long baselineTime = (endBaseline - startBaseline);
            long indexedTime = (endIndexed - startIndexed);
            if (indexedTime > 0) {
                System.out.printf("Index giúp truy vấn nhanh hơn khoảng: %.1f lần\n", (double) baselineTime / indexedTime);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void initTable(Connection conn) throws SQLException {
        String dropTable = "DROP TABLE IF EXISTS users";
        String createTable = """
                CREATE TABLE users (
                    id SERIAL PRIMARY KEY,
                    full_name VARCHAR(100),
                    detail_address VARCHAR(255),
                    ward VARCHAR(100),
                    province VARCHAR(100)
                )
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(dropTable);
            stmt.execute(createTable);
        }
    }

    private static void generateData(Connection conn, Faker faker, String[] wards, String[] provinces, int count) throws SQLException {
        System.out.println("Đang sinh " + count + " bản ghi...");
        String sql = "INSERT INTO users (full_name, detail_address, ward, province) VALUES (?, ?, ?, ?)";

        // Sử dụng Batch Insert để tăng tốc độ chèn dữ liệu
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false); // Tắt auto-commit để insert nhanh hơn

            for (int i = 0; i < count; i++) {
                pstmt.setString(1, faker.name().fullName());
                pstmt.setString(2, faker.address().streetAddress());
                pstmt.setString(3, wards[faker.random().nextInt(wards.length)]);
                pstmt.setString(4, provinces[faker.random().nextInt(provinces.length)]);
                pstmt.addBatch();

                if (i % 1000 == 0) { // Thực thi mỗi 1000 bản ghi
                    pstmt.executeBatch();
                }
            }
            pstmt.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
        }
        System.out.println("Sinh dữ liệu thành công!");
    }

    // Class phụ để map dữ liệu
    static class User {
        int id;
        String fullName;
        String detailAddress;
        String ward;
        String province;

        public User(int id, String fullName, String detailAddress, String ward, String province) {
            this.id = id;
            this.fullName = fullName;
            this.detailAddress = detailAddress;
            this.ward = ward;
            this.province = province;
        }
    }
}