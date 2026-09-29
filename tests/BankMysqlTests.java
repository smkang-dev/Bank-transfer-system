import dao.*;
import domain.*;
import exception.*;
import service.TransferService;
import util.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * 테스트용 빈 DB에 수정 schema.sql 적용 후 실행. 실제 MySQL 8.0/8.4가 필요하다.
 * BANK_TEST_DB_URL, BANK_TEST_DB_USER, BANK_TEST_DB_PASSWORD 환경변수 사용.
 * DB 이름은 반드시 _test로 끝나야 한다. DBConnection.java는 사용하지 않는다.
 */
public class BankMysqlTests {
    static String url,user,password,hash;
    static int uid,aid,bid;
    static String a,b;
    static final List<Integer> fixtureUsers=new ArrayList<>();
    static final List<Integer> fixtureAccounts=new ArrayList<>();
    static int passed;
    static Connection open() throws SQLException {return DriverManager.getConnection(url,user,password);}
    static String key(){return UUID.randomUUID().toString();}
    static TransferService service(){return new TransferService(new AccountDAO(),new TransactionDAO(),BankMysqlTests::open);}
    public static void main(String[] args) throws Exception {
        url=System.getenv("BANK_TEST_DB_URL");user=System.getenv("BANK_TEST_DB_USER");
        password=System.getenv("BANK_TEST_DB_PASSWORD");
        if(url==null || user==null || password==null)
            throw new IllegalArgumentException("BANK_TEST_DB_URL/USER/PASSWORD 필요");
        try(Connection c=open()) {
            String catalog=c.getCatalog();
            if(catalog==null || !catalog.endsWith("_test"))
                throw new IllegalArgumentException("테스트 DB 이름은 _test로 끝나야 합니다.");
            try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT @@version")) {
                rs.next();System.out.println("MySQL version: "+rs.getString(1));
            }
        }
        hash=PasswordHasher.hash("1234");
        try {
            fixture(3_000_000_000L,0,Long.MAX_VALUE);
            service().transfer(uid,a,b,2_500_000_000L,"1234",key());
            check(balance(aid)==500_000_000L && balance(bid)==2_500_000_000L,"BIGINT 저장·조회");

            fixture(1000,0,1000);
            String request=key();
            int id1=service().transfer(uid,a,b,100,"1234",request);
            int id2=service().transfer(uid,a,b,100,"1234",request);
            check(id1==id2 && balance(aid)==900 && count()==1,"동일 ID 재시도");
            expect(IllegalArgumentException.class,()->service().transfer(uid,a,b,101,"1234",request));
            check(count()==1 && balance(aid)==900,"같은 ID 다른 금액 거부");

            fixture(1000,0,1000);
            TransferService failure=new TransferService(new AccountDAO(),new TransactionDAO(){
                public int save(Connection c,Transaction t,String r,int u,LocalDate day){
                    throw new RuntimeException("injected record save failure");
                }
            },BankMysqlTests::open);
            expect(RuntimeException.class,()->failure.transfer(uid,a,b,100,"1234",key()));
            check(balance(aid)==1000 && balance(bid)==0 && count()==0,"실제 DB 부분 실패 rollback");

            fixture(1000,1000,10000);
            concurrent(8,i->service().transfer(uid,i%2==0?a:b,i%2==0?b:a,10,"1234",key()));
            check(balance(aid)==1000 && balance(bid)==1000 && count()==8,"교차 이체 총액 보존");

            fixture(1000,0,100);
            concurrent(10,i->{
                try {service().transfer(uid,a,b,20,"1234",key());}
                catch(TransferLimitExceededException expected) {}
            });
            check(balance(aid)==900 && balance(bid)==100 && count()==5,"동시 일일 한도");

            fixture(100,0,1000);
            concurrent(10,i->{
                try {service().transfer(uid,a,b,20,"1234",key());}
                catch(InsufficientBalanceException expected) {}
            });
            check(balance(aid)==0 && balance(bid)==100 && count()==5,"동시 출금 음수 잔액 방지");

            fixture(1000,0,1000);
            String shared=key();
            concurrent(8,i->service().transfer(uid,a,b,10,"1234",shared));
            check(balance(aid)==990 && count()==1,"동일 ID 동시 요청");

            fixture(1000,0,1000);
            String lost=key();
            TransferService loseReply=new TransferService(new AccountDAO(),new TransactionDAO(),()->{
                Connection real=open();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class[]{Connection.class},(p,m,args2)->{
                            try {
                                Object value=m.invoke(real,args2);
                                if(m.getName().equals("commit"))
                                    throw new SQLException("실제 commit 후 응답 유실 시뮬레이션","08006");
                                return value;
                            } catch(InvocationTargetException e){throw e.getCause();}
                        });
            });
            expect(TransferOutcomeUnknownException.class,()->loseReply.transfer(uid,a,b,100,"1234",lost));
            service().transfer(uid,a,b,100,"1234",lost);
            check(balance(aid)==900 && count()==1,"commit 응답 유실 후 재확인");

            fixture(1000,Long.MAX_VALUE,1000);
            expect(TransferFailedException.class,()->service().transfer(uid,a,b,1,"1234",key()));
            check(balance(aid)==1000 && balance(bid)==Long.MAX_VALUE && count()==0,"입금 overflow rollback");

            fixture(1000,0,1000);
            expect(UnauthorizedAccountAccessException.class,()->service().transfer(1+uid,a,b,10,"1234",key()));
            expect(InvalidAccountPasswordException.class,()->service().transfer(uid,a,b,10,"wrong",key()));
            try(Connection c=open();PreparedStatement ps=c.prepareStatement("UPDATE accounts SET status='FROZEN' WHERE account_id=?")){
                ps.setInt(1,aid);ps.executeUpdate();
            }
            expect(InvalidAccountStatusException.class,()->service().transfer(uid,a,b,10,"1234",key()));
            check(count()==0 && balance(aid)==1000,"소유권·비밀번호·상태 거부");
            fixture(1000,0,1000);
            CountDownLatch locking=new CountDownLatch(1);
            TransferService statusRace=new TransferService(new AccountDAO(){
                public List<Account> lockAccountsInOrder(Connection c,int f,int t) {
                    locking.countDown(); return super.lockAccountsInOrder(c,f,t);
                }
            },new TransactionDAO(),BankMysqlTests::open);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try(Connection blocker=open()) {
                blocker.setAutoCommit(false);
                try(PreparedStatement ps=blocker.prepareStatement("UPDATE accounts SET status='FROZEN' WHERE account_id=?")){
                    ps.setInt(1,aid);ps.executeUpdate();
                }
                Future<?> future=executor.submit(()->statusRace.transfer(uid,a,b,10,"1234",key()));
                if(!locking.await(20,TimeUnit.SECONDS))throw new AssertionError("잠금 진입 timeout");
                blocker.commit();
                try { future.get(90,TimeUnit.SECONDS); throw new AssertionError("상태 변경 미감지"); }
                catch(ExecutionException e) {
                    check(e.getCause() instanceof InvalidAccountStatusException,"잠금 전후 상태 변경 재검증");
                }
            } finally { executor.shutdownNow(); executor.awaitTermination(90,TimeUnit.SECONDS); }
            check(balance(aid)==1000 && count()==0,"상태 변경 경쟁 시 잔액 유지");

            fixture(1000,0,1000);
            for(int i=0;i<6;i++)service().transfer(uid,a,b,1,"1234",key());
            try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                    "UPDATE transactions SET t_created_at='2026-01-15 12:00:00' WHERE from_account_id=?")){
                ps.setInt(1,aid);ps.executeUpdate();
            }
            TransactionDAO query=new TransactionDAO(BankMysqlTests::open);
            Timestamp start=Timestamp.valueOf("2026-01-15 00:00:00");
            Timestamp end=Timestamp.valueOf("2026-01-16 00:00:00");
            var page1=query.findPage(aid,start,end,null,3);
            Transaction last=page1.items().get(2);
            var page2=query.findPage(aid,start,end,new TransactionDAO.Cursor(last.getCreatedAt(),last.getTransactionId()),3);
            Set<Integer> ids=new HashSet<>();
            page1.items().forEach(t->ids.add(t.getTransactionId()));
            page2.items().forEach(t->ids.add(t.getTransactionId()));
            check(page1.hasMore() && !page2.hasMore() && ids.size()==6,"동일 시각 거래의 커서 페이지 누락·중복 없음");
            check(query.findPage(aid,Timestamp.valueOf("2026-01-14 00:00:00"),start,null,3).items().isEmpty(),
                    "종료일 상한 제외");
            System.out.println("PASS "+passed+" assertions on real MySQL");
        } finally {cleanup();}
    }
    static void fixture(long source,long target,long limit) throws SQLException {
        String token=key().replace("-","").substring(0,12);
        a="TEST-A-"+token;b="TEST-B-"+token;
        try(Connection c=open()) {
            try(PreparedStatement ps=c.prepareStatement(
                    "INSERT INTO users(login_id,password,name,password_encoding) VALUES (?,?,?,'pbkdf2-sha256')",
                    Statement.RETURN_GENERATED_KEYS)){
                ps.setString(1,"test"+token);ps.setString(2,hash);ps.setString(3,"테스트");
                ps.executeUpdate();try(ResultSet rs=ps.getGeneratedKeys()){rs.next();uid=rs.getInt(1);}
                fixtureUsers.add(uid);
            }
            aid=insertAccount(c,a,source,limit);bid=insertAccount(c,b,target,limit);
        }
    }
    static int insertAccount(Connection c,String number,long balance,long limit)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO accounts "
                +"(user_id,account_number,balance,account_password,status,one_time_limit,daily_limit,password_encoding)"
                +" VALUES (?,?,?,?,'ACTIVE',?,?,'pbkdf2-sha256')",Statement.RETURN_GENERATED_KEYS)){
            ps.setInt(1,uid);ps.setString(2,number);ps.setLong(3,balance);ps.setString(4,hash);
            ps.setLong(5,Long.MAX_VALUE);ps.setLong(6,limit);ps.executeUpdate();
            try(ResultSet rs=ps.getGeneratedKeys()){
                rs.next();int id=rs.getInt(1);fixtureAccounts.add(id);return id;
            }
        }
    }
    static long balance(int id)throws SQLException{
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT balance FROM accounts WHERE account_id=?")){
            p.setInt(1,id);try(ResultSet rs=p.executeQuery()){rs.next();return rs.getLong(1);}
        }
    }
    static long count()throws SQLException{
        try(Connection c=open();PreparedStatement p=c.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id IN (?,?)")){
            p.setInt(1,aid);p.setInt(2,bid);try(ResultSet rs=p.executeQuery()){rs.next();return rs.getLong(1);}
        }
    }
    interface Task {void run()throws Exception;}
    interface IndexedTask {void run(int i)throws Exception;}
    static void concurrent(int n,IndexedTask task)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(n);
        CountDownLatch ready=new CountDownLatch(n),go=new CountDownLatch(1);
        List<Future<?>> futures=new ArrayList<>();
        try{
            for(int i=0;i<n;i++){final int index=i;
                futures.add(pool.submit(()->{ready.countDown();go.await();task.run(index);return null;}));}
            if(!ready.await(10,TimeUnit.SECONDS))throw new AssertionError("thread ready timeout");
            go.countDown();
            for(Future<?> f:futures)f.get(90,TimeUnit.SECONDS);
        }finally{
            go.countDown();pool.shutdownNow();
            if(!pool.awaitTermination(90,TimeUnit.SECONDS))
                throw new AssertionError("테스트 스레드가 종료되지 않았습니다. DB 연결을 확인하세요.");
        }
    }
    static void check(boolean ok,String label){
        if(!ok)throw new AssertionError(label);passed++;System.out.println("PASS "+label);
    }
    static void expect(Class<? extends Throwable> type,Task task)throws Exception{
        try{task.run();}catch(Throwable e){if(type.isInstance(e)){passed++;return;}throw new AssertionError(e);}
        throw new AssertionError("예외 미발생: "+type.getSimpleName());
    }
    static void cleanup()throws SQLException{
        try(Connection c=open()){
            for(int id:fixtureAccounts){
                try(PreparedStatement p=c.prepareStatement("DELETE FROM transactions WHERE from_account_id=? OR to_account_id=?")){
                    p.setInt(1,id);p.setInt(2,id);p.executeUpdate();
                }
            }
            for(int id:fixtureAccounts){
                try(PreparedStatement p=c.prepareStatement("DELETE FROM accounts WHERE account_id=?")){
                    p.setInt(1,id);p.executeUpdate();
                }
            }
            for(int id:fixtureUsers){
                try(PreparedStatement p=c.prepareStatement("DELETE FROM users WHERE user_id=?")){
                    p.setInt(1,id);p.executeUpdate();
                }
            }
        }
    }
}
