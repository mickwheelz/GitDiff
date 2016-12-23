public class AccountHandler {

  //i made some kind of change here
    //another change

    Public static Account insertNewAccount (String accountName) {

        Account newAccount = new Account(Name = accountName);

        try {
            insert newAccount;
            return newAccount;
        }
        catch (DMLException e) {

            return null;
        }
    }
}
