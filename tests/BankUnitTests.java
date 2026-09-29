import dao.*;
import domain.*;
import exception.*;
import service.TransferService;
import util.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.nio.file.*;

/** DB가 필요 없는 검사. 실제 MySQL의 잠금/롤백을 증명하는 테스트는 아니다. */
public class BankUnitTests {
    private static int passed;
    private static String hash;
    public static void main(String[] args) throws Exception {
        hash=PasswordHasher.hash("1234");
        check(PasswordHasher.matches("1234",hash),"해시 검증");
        check(!PasswordHasher.matches("wrong",hash),"오답 거부");
        check(!PasswordHasher.matches("1234","1234"),"평문 fallback 거부");
        check(!hash.equals(PasswordHasher.hash("1234")),"salt 무작위");
        check(!PasswordHasher.matches("1234","pbkdf2-sha256$1$bad$bad"),"잘못된 해시 거부");

        Fake f=new Fake();
        int id=f.service().transfer(1,"A","B",100,"1234",key());
        check(id==77 && f.commits==1 && f.withdraws==1 && f.deposits==1,"정상 결과");
        f=new Fake(); f.source.setBalance(3_000_000_000L);
        f.source.setOneTimeLimit(Long.MAX_VALUE); f.source.setDailyLimit(Long.MAX_VALUE);
        f.service().transfer(1,"A","B",2_500_000_000L,"1234",key());
        check(f.lastAmount==2_500_000_000L,"int 범위 초과 금액 전달");

        f=new Fake(); f.target.setBalance(Long.MAX_VALUE);
        Fake overflow=f;
        expect(TransferFailedException.class,()->overflow.service().transfer(1,"A","B",1,"1234",key()));
        check(f.withdraws==0 && f.commits==0,"입금 overflow 시 변경 없음");

        f=new Fake(); f.today=new BigDecimal("9223372036854775807");
        f.source.setDailyLimit(Long.MAX_VALUE);
        Fake daily=f;
        expect(TransferLimitExceededException.class,()->daily.service().transfer(1,"A","B",1,"1234",key()));
        check(f.withdraws==0,"일일 합계 overflow 없이 차단");

        f=new Fake(); f.source.setStatus("FROZEN");
        Fake frozen=f;
        expect(InvalidAccountStatusException.class,()->frozen.service().transfer(1,"A","B",1,"1234",key()));
        check(f.withdraws==0,"잠금 후 상태 검사");

        f=new Fake(); f.failSave=true;
        Fake save=f;
        expect(RuntimeException.class,()->save.service().transfer(1,"A","B",1,"1234",key()));
        check(f.rollbacks==1 && f.commits==0,"거래 저장 실패 시 rollback 호출");

        f=new Fake(); f.failCommit=true;
        Fake commit=f;
        expect(TransferOutcomeUnknownException.class,()->commit.service().transfer(1,"A","B",1,"1234",key()));
        check(f.commits==1,"commit 오류를 실패 확정으로 표현하지 않음");

        f=new Fake(); f.failClose=true;
        check(f.service().transfer(1,"A","B",1,"1234",key())==77,"close 오류로 성공을 뒤집지 않음");

        f=new Fake(); f.previous=new TransactionDAO.RequestRecord(44,1,1,2,100);
        f.source.setBalance(0); f.source.setStatus("FROZEN");
        check(f.service().transfer(1,"A","B",100,"1234",key())==44 && f.withdraws==0,"재요청은 기존 결과");
        Fake changed=f;
        expect(IllegalArgumentException.class,()->changed.service().transfer(1,"A","B",101,"1234",key()));

        String original=System.getProperty("user.home");
        Path temp=Files.createTempDirectory("bank-unit-");
        try {
            System.setProperty("user.home",temp.toString());
            var pending=new PendingTransferStore.Pending(key(),"A","B",100);
            PendingTransferStore.save(1,pending);
            check(pending.equals(PendingTransferStore.load(1)),"요청 ID 디스크 보존");
            PendingTransferStore.clear(1);
            check(PendingTransferStore.load(1)==null,"완료 요청 정리");
        } finally {
            System.setProperty("user.home",original);
            try(var paths=Files.walk(temp)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.out.println("PASS "+passed+" assertions (DB 잠금·실제 rollback 미검증)");
    }
    static String key(){return UUID.randomUUID().toString();}
    static void check(boolean ok,String label){
        if(!ok) throw new AssertionError(label); passed++; System.out.println("PASS "+label);
    }
    interface Task {void run() throws Exception;}
    static void expect(Class<? extends Throwable> type,Task task) throws Exception {
        try {task.run();} catch(Throwable e) {
            if(type.isInstance(e)){passed++;return;} throw new AssertionError("예외 타입 불일치",e);
        }
        throw new AssertionError("예외 미발생: "+type.getSimpleName());
    }
    static class Fake {
        Account source=new Account(1,1,"A",1000,hash,1000,1000,"ACTIVE",null);
        Account target=new Account(2,2,"B",1000,hash,1000,1000,"ACTIVE",null);
        int commits,rollbacks,withdraws,deposits;
        long lastAmount;
        boolean failSave,failCommit,failClose;
        BigDecimal today=BigDecimal.ZERO;
        TransactionDAO.RequestRecord previous;
        Connection connection=(Connection)Proxy.newProxyInstance(
                Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                    switch(m.getName()){
                        case "commit": commits++; if(failCommit)throw new SQLException("lost commit reply"); return null;
                        case "rollback":rollbacks++;return null;
                        case "close":if(failClose)throw new SQLException("close failure");return null;
                        case "setAutoCommit":
                            if(Boolean.TRUE.equals(a[0]))throw new AssertionError("autoCommit(true) 금지");
                            return null;
                        case "setTransactionIsolation":return null;
                        case "isClosed":return false;
                        default:throw new UnsupportedOperationException(m.getName());
                    }
                });
        TransferService service(){
            AccountDAO a=new AccountDAO(){
                public Account findByAccountNumber(Connection c,String n){return "A".equals(n)?source:target;}
                public List<Account> lockAccountsInOrder(Connection c,int a,int b){return List.of(source,target);}
                public int withdraw(Connection c,int id,long amount){withdraws++;lastAmount=amount;return 1;}
                public int deposit(Connection c,int id,long amount){deposits++;return 1;}
            };
            TransactionDAO t=new TransactionDAO(){
                public LocalDate currentDate(Connection c){return LocalDate.of(2026,9,29);}
                public BigDecimal getTransferAmount(Connection c,int id,LocalDate day){return today;}
                public RequestRecord findByRequestId(Connection c,String key){return previous;}
                public int save(Connection c,Transaction t,String key,int uid,LocalDate day){
                    if(failSave)throw new RuntimeException("injected record failure");return 77;
                }
            };
            return new TransferService(a,t,()->connection);
        }
    }
}
