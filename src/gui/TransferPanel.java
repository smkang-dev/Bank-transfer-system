package gui;

import domain.Account;
import exception.TransferOutcomeUnknownException;
import service.AccountService;
import service.TransferService;
import util.PendingTransferStore;
import util.PendingTransferStore.Pending;
import javax.swing.*;
import java.awt.*;
import java.util.UUID;

public class TransferPanel extends JPanel {
    private final MainFrame frame;
    private final AccountService accounts=new AccountService();
    private final TransferService transfers=new TransferService();
    private final JComboBox<String> fromBox=new JComboBox<>();
    private final JTextField toField=new JTextField(16);
    private final JTextField amountField=new JTextField(16);
    private final JPasswordField passwordField=new JPasswordField(16);
    private final JTextField requestField=new JTextField(36);
    private final JButton send=new JButton("이체");
    private final JButton back=new JButton("뒤로가기");
    private Pending pending;
    private boolean uncertain,busy,blocked;

    public TransferPanel(MainFrame frame) {
        this.frame=frame;
        setLayout(new GridLayout(6,2,8,8));
        add(new JLabel("출금 계좌")); add(fromBox);
        add(new JLabel("입금 계좌")); add(toField);
        add(new JLabel("금액")); add(amountField);
        add(new JLabel("계좌 비밀번호")); add(passwordField);
        add(new JLabel("요청 ID (재확인 시 유지)")); add(requestField);
        add(send); add(back);
        requestField.setEditable(false);
        send.addActionListener(e->submit());
        back.addActionListener(e->{ if(!busy) frame.showHomePanel(); });
    }

    private void controls() {
        boolean edit=!busy && pending==null && !blocked;
        fromBox.setEnabled(edit); toField.setEditable(edit); amountField.setEditable(edit);
        passwordField.setEnabled(!busy && !blocked);
        send.setEnabled(!busy && !blocked);
        send.setText(pending==null?"이체":"같은 요청으로 재확인");
        back.setEnabled(!busy);
    }

    private void submit() {
        if(busy || blocked) return;
        final int userId=frame.getLoginUser().getUserId();
        final String password=new String(passwordField.getPassword());
        try {
            if(password.isBlank()) throw new IllegalArgumentException("계좌 비밀번호를 입력하세요.");
            if(pending==null) {
                String from=(String)fromBox.getSelectedItem();
                String to=toField.getText().trim();
                long amount=Long.parseLong(amountField.getText().trim());
                if(from==null || to.isBlank() || from.equals(to) || amount<=0)
                    throw new IllegalArgumentException("계좌·금액을 확인하세요.");
                Pending candidate=new Pending(UUID.randomUUID().toString(),from,to,amount);
                PendingTransferStore.save(userId,candidate); // DB 호출 전에 기록
                pending=candidate;
                requestField.setText(candidate.requestId());
            }
        } catch(Exception e) { JOptionPane.showMessageDialog(this,e.getMessage()); return; }
        Pending request=pending;
        boolean previouslyUncertain=uncertain;
        busy=true; controls();
        new SwingWorker<Integer,Void>() {
            protected Integer doInBackground() {
                return transfers.transfer(userId,request.from(),request.to(),request.amount(),
                        password,request.requestId());
            }
            protected void done() {
                try {
                    int transactionId=get();
                    JOptionPane.showMessageDialog(TransferPanel.this,
                            "처리 성공 확인. 거래 ID: "+transactionId+" (재확인 시 기존 결과 반환)");
                    clearResolved(userId);
                } catch(Exception e) {
                    Throwable cause=e.getCause()==null?e:e.getCause();
                    if(cause instanceof TransferOutcomeUnknownException || previouslyUncertain) {
                        uncertain=true;
                        JOptionPane.showMessageDialog(TransferPanel.this,cause.getMessage()
                                +"\n요청 ID와 이체 내용을 유지합니다. 비밀번호 확인 후 같은 요청으로 재확인하세요.");
                    } else {
                        JOptionPane.showMessageDialog(TransferPanel.this,cause.getMessage());
                        clearResolved(userId); // COMMIT 시작 전 실패 + rollback 완료인 첫 시도
                    }
                } finally { busy=false; passwordField.setText(""); controls(); }
            }
        }.execute();
    }

    private void clearResolved(int userId) {
        try {
            PendingTransferStore.clear(userId);
            pending=null; uncertain=false;
            toField.setText(""); amountField.setText(""); requestField.setText("");
        } catch(Exception e) {
            // DB 결과를 뒤집지 않는다. 같은 ID를 유지하여 다음 실행에서도 중복 처리 방지.
            uncertain=true;
            JOptionPane.showMessageDialog(this,"결과는 위와 같습니다. 로컬 요청 파일 삭제 실패: "+e.getMessage());
        }
    }

    @Override public void setVisible(boolean visible) {
        super.setVisible(visible);
        if(visible && frame!=null && frame.getLoginUser()!=null && !busy) {
            try {
                int userId=frame.getLoginUser().getUserId();
                fromBox.removeAllItems();
                for(Account a:accounts.getMyAccounts(userId)) fromBox.addItem(a.getAccountNumber());
                pending=PendingTransferStore.load(userId);
                uncertain=pending!=null; blocked=false;
                if(pending!=null) {
                    fromBox.setSelectedItem(pending.from());
                    toField.setText(pending.to()); amountField.setText(Long.toString(pending.amount()));
                    requestField.setText(pending.requestId());
                } else {
                    toField.setText(""); amountField.setText(""); requestField.setText("");
                }
                passwordField.setText("");
            } catch(Exception e) {
                blocked=true;
                JOptionPane.showMessageDialog(this,e.getMessage());
            }
            controls();
        }
    }
}
