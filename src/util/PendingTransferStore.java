package util;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public final class PendingTransferStore {
    public record Pending(String requestId,String from,String to,long amount) {}
    private PendingTransferStore() {}
    private static Path path(int userId) {
        return Path.of(System.getProperty("user.home"),".bank-transfer-system","pending-"+userId+".properties");
    }
    public static Pending load(int userId) throws IOException {
        Path file=path(userId);
        if(!Files.exists(file)) return null;
        Properties p=new Properties();
        try(Reader r=Files.newBufferedReader(file)) { p.load(r); }
        try {
            String id=p.getProperty("requestId"),from=p.getProperty("from"),to=p.getProperty("to");
            long amount=Long.parseLong(p.getProperty("amount"));
            if(id==null || from==null || to==null || amount<=0) throw new IllegalArgumentException();
            return new Pending(id,from,to,amount);
        } catch(RuntimeException e) { throw new IOException("미확정 요청 파일이 손상됐습니다. 덮어쓰지 말고 확인하세요.",e); }
    }
    public static void save(int userId,Pending pending) throws IOException {
        Path target=path(userId); Files.createDirectories(target.getParent());
        Properties p=new Properties();
        p.setProperty("requestId",pending.requestId()); p.setProperty("from",pending.from());
        p.setProperty("to",pending.to()); p.setProperty("amount",Long.toString(pending.amount()));
        Path temp=Files.createTempFile(target.getParent(),"pending-",".tmp");
        try {
            try(Writer w=Files.newBufferedWriter(temp)) { p.store(w,"Pending transfer; no password stored"); }
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) {
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }
    public static void clear(int userId) throws IOException { Files.deleteIfExists(path(userId)); }
}
