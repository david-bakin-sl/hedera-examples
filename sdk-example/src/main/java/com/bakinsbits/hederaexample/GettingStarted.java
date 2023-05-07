package com.bakinsbits.hederaexample;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hedera.hashgraph.sdk.AccountBalanceQuery;
import com.hedera.hashgraph.sdk.AccountCreateTransaction;
import com.hedera.hashgraph.sdk.AccountId;
import com.hedera.hashgraph.sdk.Client;
import com.hedera.hashgraph.sdk.ContractCallQuery;
import com.hedera.hashgraph.sdk.ContractCreateTransaction;
import com.hedera.hashgraph.sdk.ContractExecuteTransaction;
import com.hedera.hashgraph.sdk.ContractFunctionParameters;
import com.hedera.hashgraph.sdk.ContractId;
import com.hedera.hashgraph.sdk.FileCreateTransaction;
import com.hedera.hashgraph.sdk.FileId;
import com.hedera.hashgraph.sdk.Hbar;
import com.hedera.hashgraph.sdk.PrecheckStatusException;
import com.hedera.hashgraph.sdk.PrivateKey;
import com.hedera.hashgraph.sdk.PublicKey;
import com.hedera.hashgraph.sdk.ReceiptStatusException;
import com.hedera.hashgraph.sdk.TransferTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

// Tutorials from: https://docs.hedera.com/hedera/getting-started/try-examples

public class GettingStarted {

  public record AccountInfo(
      @NonNull AccountId accountId, @NonNull PrivateKey privateKey, @NonNull PublicKey publicKey) {}

  public record ContractInfo(@NonNull FileId fileId, @NonNull ContractId contractId) {}

  public static void main(String[] args)
      throws PrecheckStatusException,
          TimeoutException,
          ReceiptStatusException,
          InterruptedException {

    // https://docs.hedera.com/hedera/getting-started/environment-set-up

    AccountInfo myPrimaryTestnetAccount = null;
    {
      var myAccountId = AccountId.fromString(Dotenv.load().get("MY_ACCOUNT_ID"));
      var myPrivateKey = PrivateKey.fromString(Dotenv.load().get("MY_PRIVATE_KEY"));
      var myPublicKey = PublicKey.fromString(Dotenv.load().get("MY_PUBLIC_KEY"));
      myPrimaryTestnetAccount = new AccountInfo(myAccountId, myPrivateKey, myPublicKey);
    }

    var client = Client.forTestnet();
    client.setOperator(myPrimaryTestnetAccount.accountId(), myPrimaryTestnetAccount.privateKey());

    AccountInfo newAccount = null;
    ContractInfo newContract = null;
    String contractMessage = null;

    for (var arg : args) {
      switch (arg.toLowerCase()) {
        case "ca" -> newAccount = createAnAccountAndTransferHbar(myPrimaryTestnetAccount, client);
        case "dsc" -> newContract = deploySmartContract(client);
        case "csc" -> {
          Objects.requireNonNull(newContract);
          contractMessage = callSmartContract(client, newContract);
        }
        case "mcs" -> {
          Objects.requireNonNull(newContract);
          modifyContractState(client, newContract);
        }
        case "sl" -> {
          System.out.printf("Sleeping 1s...%n");
          Thread.sleep(1000);
        }

        default -> System.out.printf("*** Unknown argument: '%s'%n", args[0]);
      }
    }
  }

  private static void modifyContractState(
      @NonNull final Client client, @NonNull final ContractInfo contractInfo)
      throws ReceiptStatusException, PrecheckStatusException, TimeoutException {
    // https://docs.hedera.com/hedera/getting-started/try-examples/deploy-your-first-smart-contract#5.-call-the-set_message-contract-function
    System.out.printf("MCS: modifyContractState%n");

    var contractExecTx =
        new ContractExecuteTransaction()
            .setContractId(contractInfo.contractId())
            .setGas(100_000)
            .setFunction(
                "set_message",
                new ContractFunctionParameters().addString("Hello from Hedera, again!"));
    var contractExecTxResponse = contractExecTx.execute(client);
    var contractExecTxReceipt = contractExecTxResponse.getReceipt(client);

    System.out.printf("set_message status: %s%n", contractExecTxReceipt.status);
  }

  private static String callSmartContract(
      @NonNull final Client client, @NonNull final ContractInfo contractInfo)
      throws PrecheckStatusException, TimeoutException {
    // https://docs.hedera.com/hedera/getting-started/try-examples/deploy-your-first-smart-contract#4.-call-the-get_message-contract-function
    System.out.printf("CSC: callSmartContract%n");

    var contractQuery =
        new ContractCallQuery()
            .setGas(100_000)
            .setContractId(contractInfo.contractId())
            .setFunction("get_message")
            .setQueryPayment(new Hbar(2));
    var contractQueryResponse = contractQuery.execute(client);
    var message = contractQueryResponse.getString(0);

    System.out.printf("get_message() returns: %s%n", message);

    return message;
  }

  private static byte[] getContractBytecodeFromResource(
      @NonNull final String resourceName, final String... pathComponents) {
    final var root = getJsonResource(resourceName);
    final var leaf = getJsonPrimitive(root, pathComponents);
    return leaf.getAsString().getBytes(StandardCharsets.UTF_8);
  }

  private static JsonObject getJsonResource(@NonNull final String resourceName) {
    var gson = new Gson();
    var jsonStream = GettingStarted.class.getClassLoader().getResourceAsStream(resourceName);
    Objects.requireNonNull(jsonStream, "resource not found: '%s'".formatted(resourceName));
    var jsonObject =
        gson.fromJson(new InputStreamReader(jsonStream, StandardCharsets.UTF_8), JsonObject.class);
    Objects.requireNonNull(jsonObject, "json not parsed: '%s'".formatted(resourceName));
    return jsonObject;
  }

  private static JsonPrimitive getJsonPrimitive(
      @NonNull final JsonObject root, final String... pathComponents) {
    var jsonObject = root;
    for (final var c : Arrays.stream(pathComponents).limit(pathComponents.length - 1).toList()) {
      jsonObject = jsonObject.getAsJsonObject(c);
    }
    return jsonObject.getAsJsonPrimitive(pathComponents[pathComponents.length - 1]);
  }

  private static ContractInfo deploySmartContract(@NonNull final Client client)
      throws PrecheckStatusException, TimeoutException, ReceiptStatusException {
    // https://docs.hedera.com/hedera/getting-started/try-examples/deploy-your-first-smart-contract#2.-store-the-smart-contract-bytecode-on-hedera
    // https://docs.hedera.com/hedera/getting-started/try-examples/deploy-your-first-smart-contract#3.-deploy-a-hedera-smart-contract
    System.out.printf("DSC: deploySmartContract%n");

    // Load smart contract from json and store it on Hedera.  Uses `FileCreateTransaction`
    // (alternative is to use `CreateContractFlow` to create file and deploy in one go)

    var bytecode =
        getContractBytecodeFromResource(
            "solidity/HelloHedera.json",
            "contracts",
            "src/main/solidity/HelloHedera.sol:HelloHedera",
            "bin");

    // Create a file on Hedera containing the contract's bytecodes (hex-encoded); client
    // has account to pay the fee
    var fileCreateTx = new FileCreateTransaction().setContents(bytecode);
    var fileCreateTxResponse = fileCreateTx.execute(client);
    var submitFileReceipt = fileCreateTxResponse.getReceipt(client);
    var bytecodeFileId = submitFileReceipt.fileId;
    Objects.requireNonNull(bytecodeFileId, "how did bytecodeFileId get returned as null?");

    System.out.printf("New contract file id: %s%n", bytecodeFileId);

    // Instantiate contract instance
    var contractCreateTx =
        new ContractCreateTransaction()
            .setBytecodeFileId(bytecodeFileId)
            .setGas(100_000)
            .setConstructorParameters(
                new ContractFunctionParameters().addString("Hello from Hedera!"));
    var contractCreateTxResponse = contractCreateTx.execute(client);
    var contractCreateReceipt = contractCreateTxResponse.getReceipt(client);
    var contractId = contractCreateReceipt.contractId;
    Objects.requireNonNull(contractId, "how did contractId get returned as null?");

    System.out.printf("New contract id:%s%n", contractId);

    return new ContractInfo(bytecodeFileId, contractId);
  }

  private static AccountInfo createAnAccountAndTransferHbar(
      @NonNull final AccountInfo startAccount, @NonNull final Client client)
      throws TimeoutException, PrecheckStatusException, ReceiptStatusException {
    // https://docs.hedera.com/hedera/getting-started/create-an-account
    System.out.printf("CA: createAnAccountAndTransferHbar%n");

    // Generate a new key pair
    var newAccountPrivateKey = PrivateKey.generateED25519();
    var newAccountPublicKey = newAccountPrivateKey.getPublicKey();

    System.out.printf(
        "New ED25519 keypair:%npriv DER: %s%npub  DER: %s%n",
        newAccountPrivateKey.toStringDER(), newAccountPublicKey.toStringDER());

    // Create new account and assign the public key
    var newAccountResponse =
        new AccountCreateTransaction()
            .setKey(newAccountPublicKey)
            .setInitialBalance(Hbar.fromTinybars(1000))
            .execute(client);

    // Get the new account ID
    var newAccountId = newAccountResponse.getReceipt(client).accountId;
    Objects.requireNonNull(newAccountId);

    var newAccount = new AccountInfo(newAccountId, newAccountPrivateKey, newAccountPublicKey);

    // Log the account ID
    System.out.printf("Creating new account transaction response: %s%n", newAccount);
    System.out.println("The new account ID is: " + newAccountId);

    //    // Use previously created account
    //    AccountId newAccountId = AccountId.fromString("0.0.49397906");
    //    System.out.printf("The new (2nd) account ID is: %s%n", newAccountId);

    // Check the new account's balance
    var accountBalance = new AccountBalanceQuery().setAccountId(newAccountId).execute(client);

    System.out.printf("Account balance query result: %s%n", accountBalance);
    System.out.println("The new account balance is: " + accountBalance.hbars);

    // https://docs.hedera.com/hedera/getting-started/transfer-hbar

    // Create a transfer transaction (net value of the transfer must equal zero)
    var sendHbar =
        new TransferTransaction()
            .addHbarTransfer(startAccount.accountId(), Hbar.fromTinybars(-1000)) // Sending account
            .addHbarTransfer(newAccountId, Hbar.fromTinybars(1000)) // Receiving account
            .execute(client);

    System.out.printf("Transfer transaction result: %s%n", sendHbar);

    // Verify that it reached consensus
    var xferReceipt = sendHbar.getReceipt(client);
    var xferStatus = xferReceipt.status;

    System.out.printf("Transaction receipt: %s%nTransaction status: %s%n", xferReceipt, xferStatus);

    // Get account balance
    // Verify cost of query is 0
    var queryCost = new AccountBalanceQuery().setAccountId(newAccountId).getCost(client);

    System.out.printf("Cost to ask account balance: %s%n", queryCost);

    // Get balance now
    var accountBalanceNew = new AccountBalanceQuery().setAccountId(newAccountId).execute(client);

    System.out.printf("Account balance query result: %s%n", accountBalanceNew);
    System.out.printf("New account balance is: %s%n", accountBalanceNew.hbars);

    return newAccount;
  }
}
