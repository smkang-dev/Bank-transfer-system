package gui;

import dao.TransactionDAO;
import domain.Account;
import domain.Transaction;
import service.AccountService;
import service.HistoryService;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class HistoryPanel extends JPanel {
    private final MainFrame mainFrame;
    private final AccountService accounts = new AccountService();
    private final HistoryService history = new HistoryService();
    private final JComboBox<String> accountBox = new JComboBox<>();
    private final JTextField startField = new JTextField(10);
    private final JTextField endField = new JTextField(10);
    private final JButton nextButton = new JButton("다음 50건");
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"거래ID","구분","상대 계좌ID","금액","유형","상태","거래일시"},0) {
        @Override public boolean isCellEditable(int r,int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private List<Account> accountList = new ArrayList<>();
    private TransactionDAO.Cursor cursor;
    private LocalDate activeStart, activeEnd;
    private int activeAccountId;
    private boolean busy;

    public HistoryPanel(MainFrame frame) {
        this.mainFrame = frame;
        setLayout(new BorderLayout(8,8));
        JPanel filters = new JPanel(new FlowLayout());
        JButton all = new JButton("전체 기간 / 처음");
        JButton period = new JButton("기간 조회 / 처음");
        JButton detail = new JButton("상세");
        JButton back = new JButton("뒤로가기");
        filters.add(accountBox); filters.add(all);
        filters.add(new JLabel("시작일")); filters.add(startField);
        filters.add(new JLabel("종료일")); filters.add(endField);
        filters.add(period); filters.add(detail); filters.add(back);
        add(filters,BorderLayout.NORTH);
        add(new JScrollPane(table),BorderLayout.CENTER);
        add(nextButton,BorderLayout.SOUTH);
        nextButton.setEnabled(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        all.addActionListener(e -> startSearch(false));
        period.addActionListener(e -> startSearch(true));
        nextButton.addActionListener(e -> loadPage());
        back.addActionListener(e -> { if (!busy) frame.showHomePanel(); });
        detail.addActionListener(e -> showDetail());
        accountBox.addActionListener(e -> {
            if (!busy) { model.setRowCount(0); cursor=null; nextButton.setEnabled(false); }
        });
    }

    private void startSearch(boolean period) {
        if (busy) return;
        try {
            int index=accountBox.getSelectedIndex();
            if (index<0) throw new IllegalArgumentException("계좌가 없습니다.");
            LocalDate start=period?LocalDate.parse(startField.getText().trim()):null;
            LocalDate end=period?LocalDate.parse(endField.getText().trim()):null;
            if (start!=null && start.isAfter(end))
                throw new IllegalArgumentException("시작일이 종료일보다 늦습니다.");
            activeAccountId=accountList.get(index).getAccountId();
            activeStart=start; activeEnd=end; cursor=null;
            loadPage();
        } catch (Exception e) { JOptionPane.showMessageDialog(this,"날짜는 yyyy-MM-dd 형식입니다. "+e.getMessage()); }
    }

    private void loadPage() {
        if (busy || activeAccountId==0) return;
        busy=true; accountBox.setEnabled(false); nextButton.setEnabled(false);
        int userId=mainFrame.getLoginUser().getUserId();
        new SwingWorker<TransactionDAO.Page,Void>() {
            protected TransactionDAO.Page doInBackground() {
                return history.getPage(activeAccountId,userId,activeStart,activeEnd,cursor,50);
            }
            protected void done() {
                try {
                    TransactionDAO.Page page=get();
                    model.setRowCount(0);
                    for (Transaction t:page.items()) {
                        int other=t.getFromAccountId()==activeAccountId?t.getToAccountId():t.getFromAccountId();
                        model.addRow(new Object[]{t.getTransactionId(),history.getDirection(activeAccountId,t),
                                other,t.getAmount(),t.getType(),t.getStatus(),t.getCreatedAt()});
                    }
                    if (!page.items().isEmpty()) {
                        Transaction last=page.items().get(page.items().size()-1);
                        cursor=new TransactionDAO.Cursor(last.getCreatedAt(),last.getTransactionId());
                    }
                    nextButton.setEnabled(page.hasMore());
                } catch (Exception e) {
                    Throwable cause=e.getCause()==null?e:e.getCause();
                    JOptionPane.showMessageDialog(HistoryPanel.this,cause.getMessage());
                    nextButton.setEnabled(cursor!=null);
                } finally { busy=false; accountBox.setEnabled(true); }
            }
        }.execute();
    }

    private void showDetail() {
        if (busy || table.getSelectedRow()<0) return;
        try {
            int id=(int)model.getValueAt(table.getSelectedRow(),0);
            Transaction t=history.getTransactionDetail(id,activeAccountId,mainFrame.getLoginUser().getUserId());
            JOptionPane.showMessageDialog(this,t.toString(),"거래 상세",JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) { JOptionPane.showMessageDialog(this,e.getMessage()); }
    }

    @Override public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible && mainFrame!=null && mainFrame.getLoginUser()!=null && !busy) {
            try {
                accountList=accounts.getMyAccounts(mainFrame.getLoginUser().getUserId());
                accountBox.removeAllItems();
                for (Account a:accountList) accountBox.addItem(a.getAccountNumber());
                startSearch(false);
            } catch (Exception e) { JOptionPane.showMessageDialog(this,e.getMessage()); }
        }
    }
}
