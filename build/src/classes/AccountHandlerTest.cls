@isTest
public class AccountHandlerTest {
    
    static testMethod void doStuff() {
        AccountHandler.insertNewAccount('Test Acct');
    }
    
    static testMethod void doStuff2() {
        AccountHandler.insertNewAccount(null);
    }
    
}