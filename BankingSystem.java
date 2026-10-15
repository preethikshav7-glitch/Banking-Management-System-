import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * NovaBank - Banking Management System
 * Requires Java 17+.
 *
 * Compile: javac BankingSystem.java
 * Run:     java BankingSystem
 */
public class BankingSystem {

    static final BigDecimal FRAUD_THRESHOLD = new BigDecimal("50000");
    static final String DATA_FILE = "bank_data.csv";
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    enum TxType { DEPOSIT, WITHDRAW, TRANSFER_IN, TRANSFER_OUT, INTEREST, REVERSAL }

    /** Immutable ledger entry. */
    record Transaction(TxType type, BigDecimal amount, BigDecimal balanceAfter,
                       LocalDateTime at, String note) {
        @Override
        public String toString() {
            return String.format("%-19s %-13s %12s %12s  %s",
                    at.format(FMT), type, amount, balanceAfter, note);
        }
    }

    static class BankException extends RuntimeException {
        BankException(String message) { super(message); }
    }

    // ------------------------------------------------------------------
    // Accounts (polymorphism: each subtype defines its own rules)
    // ------------------------------------------------------------------
    static abstract class Account {
        private final String number;
        private final String holder;
        private BigDecimal balance;
        private final List<Transaction> history = new ArrayList<>();

        Account(String number, String holder, BigDecimal opening) {
            this.number = number;
            this.holder = holder;
            this.balance = opening.setScale(2, RoundingMode.HALF_EVEN);
        }

        String number() { return number; }
        String holder() { return holder; }
        BigDecimal balance() { return balance; }
        List<Transaction> history() { return Collections.unmodifiableList(history); }

        abstract String kind();
        abstract BigDecimal overdraftLimit();
        abstract BigDecimal annualRate();

        BigDecimal available() { return balance.add(overdraftLimit()); }

        void deposit(BigDecimal amt, TxType type, String note) {
            requirePositive(amt);
            balance = balance.add(amt);
            log(type, amt, note);
        }

        void withdraw(BigDecimal amt, TxType type, String note) {
            requirePositive(amt);
            if (available().compareTo(amt) < 0)
                throw new BankException("Insufficient funds in " + number
                        + " (available: " + available() + ")");
            balance = balance.subtract(amt);
            log(type, amt, note);
        }

        void applyMonthlyInterest() {
            if (annualRate().signum() == 0) return;
            BigDecimal interest = balance.multiply(annualRate())
                    .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_EVEN);
            if (interest.signum() > 0) {
                balance = balance.add(interest);
                log(TxType.INTEREST, interest, "Monthly interest");
            }
        }

        private void log(TxType type, BigDecimal amt, String note) {
            history.add(new Transaction(type, amt, balance, LocalDateTime.now(), note));
        }

        private static void requirePositive(BigDecimal amt) {
            if (amt == null || amt.signum() <= 0)
                throw new BankException("Amount must be greater than zero");
        }
    }

    static class SavingsAccount extends Account {
        SavingsAccount(String n, String h, BigDecimal o) { super(n, h, o); }
        String kind() { return "SAVINGS"; }
        BigDecimal overdraftLimit() { return BigDecimal.ZERO; }
        BigDecimal annualRate() { return new BigDecimal("0.04"); }
    }

    static class CurrentAccount extends Account {
        CurrentAccount(String n, String h, BigDecimal o) { super(n, h, o); }
        String kind() { return "CURRENT"; }
        BigDecimal overdraftLimit() { return new BigDecimal("5000"); }
        BigDecimal annualRate() { return BigDecimal.ZERO; }
    }

    // ------------------------------------------------------------------
    // Bank: orchestration, observers, undo, persistence
    // ------------------------------------------------------------------
    interface BankListener { void onEvent(String event); }

    static class Bank {
        record Operation(TxType type, String from, String to, BigDecimal amount) {}

        private final Map<String, Account> accounts = new LinkedHashMap<>();
        private final List<BankListener> listeners = new ArrayList<>();
        private final Deque<Operation> undoStack = new ArrayDeque<>();
        private int seq = 1000;

        void subscribe(BankListener l) { listeners.add(l); }

        private void emit(String msg) {
            for (BankListener l : listeners) l.onEvent(msg);
        }

        Account open(String kind, String holder, BigDecimal opening) {
            String no = "AC" + (++seq);
            Account a = switch (kind.toUpperCase()) {
                case "SAVINGS" -> new SavingsAccount(no, holder, opening);
                case "CURRENT" -> new CurrentAccount(no, holder, opening);
                default -> throw new BankException("Unknown account type: " + kind);
            };
            accounts.put(no, a);
            emit("Account " + no + " opened for " + holder);
            return a;
        }

        Account find(String no) {
            Account a = accounts.get(no);
            if (a == null) throw new BankException("No such account: " + no);
            return a;
        }

        Collection<Account> all() { return accounts.values(); }

        void deposit(String no, BigDecimal amt) {
            find(no).deposit(amt, TxType.DEPOSIT, "Cash deposit");
            undoStack.push(new Operation(TxType.DEPOSIT, null, no, amt));
        }

        void withdraw(String no, BigDecimal amt) {
            find(no).withdraw(amt, TxType.WITHDRAW, "Cash withdrawal");
            undoStack.push(new Operation(TxType.WITHDRAW, no, null, amt));
            checkFraud(no, amt);
        }

        /** Atomic transfer: if crediting fails, the debit is rolled back. */
        void transfer(String from, String to, BigDecimal amt) {
            if (from.equals(to)) throw new BankException("Cannot transfer to the same account");
            Account src = find(from);
            Account dst = find(to);
            src.withdraw(amt, TxType.TRANSFER_OUT, "To " + to);
            try {
                dst.deposit(amt, TxType.TRANSFER_IN, "From " + from);
            } catch (RuntimeException e) {
                src.deposit(amt, TxType.REVERSAL, "Rollback of failed transfer");
                throw e;
            }
            undoStack.push(new Operation(TxType.TRANSFER_OUT, from, to, amt));
            checkFraud(from, amt);
        }

        /** Reverses the most recent deposit, withdrawal, or transfer. */
        void undoLast() {
            Operation op = undoStack.peek();
            if (op == null) throw new BankException("Nothing to undo");
            switch (op.type()) {
                case DEPOSIT -> find(op.to()).withdraw(op.amount(), TxType.REVERSAL, "Undo deposit");
                case WITHDRAW -> find(op.from()).deposit(op.amount(), TxType.REVERSAL, "Undo withdrawal");
                case TRANSFER_OUT -> {
                    find(op.to()).withdraw(op.amount(), TxType.REVERSAL, "Undo transfer");
                    find(op.from()).deposit(op.amount(), TxType.REVERSAL, "Undo transfer");
                }
                default -> throw new BankException("Operation cannot be undone");
            }
            undoStack.pop();
            emit("Undid " + op.type() + " of " + op.amount());
        }

        void runMonthEnd() {
            for (Account a : accounts.values()) a.applyMonthlyInterest();
            emit("Month-end interest posted to " + accounts.size() + " account(s)");
        }

        private void checkFraud(String no, BigDecimal amt) {
            if (amt.compareTo(FRAUD_THRESHOLD) >= 0)
                emit("[ALERT] Large movement of " + amt + " on account " + no);
        }

        void save() throws IOException {
            try (PrintWriter pw = new PrintWriter(new FileWriter(DATA_FILE))) {
                for (Account a : accounts.values())
                    pw.println(a.number() + "," + a.kind() + "," + a.holder() + "," + a.balance());
            }
        }

        void load() throws IOException {
            File f = new File(DATA_FILE);
            if (!f.exists()) return;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",");
                    if (p.length != 4) continue;
                    BigDecimal bal = new BigDecimal(p[3]);
                    Account a = p[1].equals("SAVINGS")
                            ? new SavingsAccount(p[0], p[2], bal)
                            : new CurrentAccount(p[0], p[2], bal);
                    accounts.put(p[0], a);
                    seq = Math.max(seq, Integer.parseInt(p[0].substring(2)));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Console interface
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        Bank bank = new Bank();
        bank.subscribe(msg -> System.out.println("  [EVENT] " + msg));

        try {
            bank.load();
        } catch (IOException e) {
            System.out.println("Could not load data: " + e.getMessage());
        }

        System.out.println("=== Welcome to NovaBank Management System ===");
        boolean running = true;

        while (running) {
            printMenu();
            String choice = in.nextLine().trim();
            try {
                switch (choice) {
                    case "1" -> {
                        String type = ask(in, "Type (SAVINGS/CURRENT)");
                        String name = ask(in, "Holder name");
                        BigDecimal opening = money(in, "Opening deposit");
                        Account a = bank.open(type, name, opening);
                        System.out.println("Created " + a.kind() + " account " + a.number());
                    }
                    case "2" -> bank.deposit(ask(in, "Account no"), money(in, "Amount"));
                    case "3" -> bank.withdraw(ask(in, "Account no"), money(in, "Amount"));
                    case "4" -> bank.transfer(ask(in, "From account"), ask(in, "To account"),
                            money(in, "Amount"));
                    case "5" -> showAccount(bank.find(ask(in, "Account no")));
                    case "6" -> bank.undoLast();
                    case "7" -> bank.runMonthEnd();
                    case "8" -> showAll(bank);
                    case "9" -> {
                        bank.save();
                        System.out.println("Data saved. Goodbye!");
                        running = false;
                    }
                    default -> System.out.println("Invalid choice, try again.");
                }
            } catch (BankException e) {
                System.out.println("Error: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("File error: " + e.getMessage());
            }
            System.out.println();
        }
        in.close();
    }

    static void printMenu() {
        System.out.println("""
                1. Open account
                2. Deposit
                3. Withdraw
                4. Transfer
                5. View account and statement
                6. Undo last operation
                7. Run month-end interest
                8. List all accounts
                9. Save and exit""");
        System.out.print("Choice: ");
    }

    static void showAccount(Account a) {
        System.out.println("\nAccount   : " + a.number());
        System.out.println("Holder    : " + a.holder());
        System.out.println("Type      : " + a.kind());
        System.out.println("Balance   : " + a.balance());
        System.out.println("Available : " + a.available());
        System.out.println("\n" + String.format("%-19s %-13s %12s %12s  %s",
                "DATE", "TYPE", "AMOUNT", "BALANCE", "NOTE"));
        for (Transaction t : a.history()) System.out.println(t);
    }

    static void showAll(Bank bank) {
        System.out.printf("%-8s %-9s %-20s %14s%n", "ACC NO", "TYPE", "HOLDER", "BALANCE");
        BigDecimal total = BigDecimal.ZERO;
        for (Account a : bank.all()) {
            System.out.printf("%-8s %-9s %-20s %14s%n",
                    a.number(), a.kind(), a.holder(), a.balance());
            total = total.add(a.balance());
        }
        System.out.println("Total deposits under management: " + total);
    }

    static String ask(Scanner in, String prompt) {
        System.out.print(prompt + ": ");
        return in.nextLine().trim();
    }

    static BigDecimal money(Scanner in, String prompt) {
        try {
            return new BigDecimal(ask(in, prompt)).setScale(2, RoundingMode.HALF_EVEN);
        } catch (NumberFormatException e) {
            throw new BankException("Invalid amount");
        }
    }
}
