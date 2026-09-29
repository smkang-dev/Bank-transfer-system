package main;

import util.DBConnection;
import util.PasswordHasher;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 앱 종료 후 한 번 실행. 처리한 행에는 형식 플래그를 남겨 중단 후 재실행 가능. */
public class MigratePasswords {
    private record Row(int id,String password) {}
    public static void main(String[] args) throws Exception {
        if (args.length!=1 || !"--confirm-backup".equals(args[0]))
            throw new IllegalArgumentException("앱 종료·DB 백업 후 --confirm-backup 인자로 실행하세요.");
        try (Connection conn=DBConnection.getConnection()) {
            migrate(conn,"users","user_id","password");
            migrate(conn,"accounts","account_id","account_password");
        }
        System.out.println("비밀번호 변환 완료. 기존 입력 비밀번호로 로그인/이체 가능합니다.");
    }
    private static void migrate(Connection conn,String table,String id,String column) throws SQLException {
        int count=0;
        while (true) {
            List<Row> rows=new ArrayList<>();
            try (Statement ps=conn.createStatement();
                 ResultSet rs=ps.executeQuery("SELECT "+id+","+column+" FROM "+table
                         +" WHERE password_encoding='plain' ORDER BY "+id+" LIMIT 100")) {
                while(rs.next()) rows.add(new Row(rs.getInt(1),rs.getString(2)));
            }
            if(rows.isEmpty()) break;
            for(Row r:rows) {
                String hash=PasswordHasher.hash(r.password());
                try(PreparedStatement ps=conn.prepareStatement("UPDATE "+table+" SET "+column
                        +"=?,password_encoding='pbkdf2-sha256' WHERE "+id
                        +"=? AND password_encoding='plain' AND BINARY "+column+"=BINARY ?")) {
                    ps.setString(1,hash); ps.setInt(2,r.id()); ps.setString(3,r.password());
                    if(ps.executeUpdate()!=1) throw new SQLException("변환 중 데이터 변경 감지. 앱을 종료하세요.");
                    count++;
                }
            }
            System.out.println(table+" 변환 행 수: "+count);
        }
    }
}
