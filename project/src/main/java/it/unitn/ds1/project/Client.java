package it.unitn.ds1.project;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import it.unitn.ds1.project.message.ClientWelcomeMsg;
import it.unitn.ds1.project.message.StopMsg;
import it.unitn.ds1.project.message.txn.begin.TxnAcceptMsg;
import it.unitn.ds1.project.message.txn.begin.TxnAcceptTimeoutMsg;
import it.unitn.ds1.project.message.txn.begin.TxnBeginMsg;
import it.unitn.ds1.project.message.txn.end.TxnEndMsg;
import it.unitn.ds1.project.message.txn.end.TxnResultMsg;
import it.unitn.ds1.project.message.txn.read.TxnReadRequestMsg;
import it.unitn.ds1.project.message.txn.read.TxnReadResultMsg;
import it.unitn.ds1.project.message.txn.write.TxnWriteRequestMsg;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Client extends AbstractActor {

    private static final double COMMIT_PROBABILITY = 0.8;
    private static final double WRITE_PROBABILITY = 0.5;

    private static final int MIN_TXN_LENGTH = 20;
    private static final int MAX_TXN_LENGTH = 40;
    private static final int RAND_LENGTH_RANGE = MAX_TXN_LENGTH - MIN_TXN_LENGTH + 1;

    private final Integer clientId;
    private List<ActorRef> coordinators;

    // the maximum key associated to items of the store
    private Integer maxKey;

    // keep track of the number of TXNs (attempted, successfully committed)
    private Integer numAttemptedTxn;
    private Integer numCommittedTxn;

    // TXN operation (move some amount from a value to another)
    private Boolean acceptedTxn;
    private ActorRef currentCoordinator;
    private Integer firstKey, secondKey;
    private Integer firstValue, secondValue;
    private Integer numOpTotal;
    private Integer numOpDone;
    private Cancellable acceptTimeout;
    private final Random r;

    /*-- Actor constructor ---------------------------------------------------- */

    public Client(int clientId) {
        this.clientId = clientId;
        this.numAttemptedTxn = 0;
        this.numCommittedTxn = 0;
        this.r = new Random();
    }

    static public Props props(int clientId) {
        return Props.create(Client.class, () -> new Client(clientId));
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ClientWelcomeMsg.class, this::onClientWelcome)
                .match(StopMsg.class, this::onStop)
                // CLIENT <-- COORDINATOR
                .match(TxnAcceptMsg.class, this::onTxnAccept)
                .match(TxnAcceptTimeoutMsg.class, this::onTxnAcceptTimeout)
                .match(TxnReadResultMsg.class, this::onReadResult)
                .match(TxnResultMsg.class, this::onTxnResult)
                .build();
    }

    /*-- Actor methods -------------------------------------------------------- */

    // start a new TXN: choose a random coordinator, send TxnBeginMsg and set timeout
    void beginTxn() {
        // some delay between transactions from the same client
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        acceptedTxn = false;
        numAttemptedTxn++;

        // contact a random coordinator and begin TXN
        currentCoordinator = coordinators.get(r.nextInt(coordinators.size()));
        currentCoordinator.tell(new TxnBeginMsg(clientId), getSelf());

        // how many operations (taking some amount and adding it somewhere else)?
        int numExtraOp = RAND_LENGTH_RANGE > 0 ? r.nextInt(RAND_LENGTH_RANGE) : 0;
        numOpTotal = MIN_TXN_LENGTH + numExtraOp;
        numOpDone = 0;

        // timeout for confirmation of TXN by the coordinator (sent to self)
        acceptTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(5000, TimeUnit.MILLISECONDS),
                getSelf(),
                new TxnAcceptTimeoutMsg(), // message sent to myself
                getContext().system().dispatcher(), getSelf()
        );
        log("BEGIN");
    }

    // end the current TXN sending TxnEndMsg to the coordinator
    void endTxn() {
        boolean doCommit = r.nextDouble() < COMMIT_PROBABILITY;
        currentCoordinator.tell(new TxnEndMsg(clientId, doCommit), getSelf());
        firstValue = null;
        secondValue = null;
        log("END");
    }

    // READ two items (will move some amount from the value of the first to the second)
    void readTwo() {
        // read two different keys
        firstKey = r.nextInt(maxKey + 1);
        int randKeyOffset = 1 + r.nextInt(maxKey - 1);
        secondKey = (firstKey + randKeyOffset) % (maxKey + 1);

        // READ requests
        currentCoordinator.tell(new TxnReadRequestMsg(clientId, firstKey), getSelf());
        currentCoordinator.tell(new TxnReadRequestMsg(clientId, secondKey), getSelf());

        // delete the current read values
        firstValue = null;
        secondValue = null;
        log("READ #" + numOpDone + " (" + firstKey + "), (" + secondKey + ")");
    }

    // WRITE two items (called with probability WRITE_PROBABILITY after readTwo() values are returned)
    void writeTwo() {
        // take some amount from one value and pass it to the other, then request writes
        Integer amountTaken = 0;
        if (firstValue >= 1) amountTaken = 1 + r.nextInt(firstValue);
        currentCoordinator.tell(new TxnWriteRequestMsg(clientId, firstKey, firstValue - amountTaken), getSelf());
        currentCoordinator.tell(new TxnWriteRequestMsg(clientId, secondKey, secondValue + amountTaken), getSelf());
        log("WRITE #" + numOpDone
                + " taken " + amountTaken
                + " (" + firstKey + ", " + (firstValue - amountTaken) + "), ("
                + secondKey + ", " + (secondValue + amountTaken) + ")");
    }

    /*-- General messages ----------------------------------------------------- */

    private void onClientWelcome(ClientWelcomeMsg msg) {
        this.coordinators = msg.coordinators;
        System.out.println(coordinators);
        this.maxKey = msg.maxKey;
        beginTxn();
    }

    private void onStop(StopMsg msg) {
        getContext().stop(getSelf());
    }

    /*-- Transaction messages ----------------------------------------------------- */

    private void onTxnAccept(TxnAcceptMsg msg) {
        log("received TxnAccept");
        acceptedTxn = true;
        acceptTimeout.cancel();
        readTwo();
    }

    private void onTxnAcceptTimeout(TxnAcceptTimeoutMsg msg) throws InterruptedException {
        if (!acceptedTxn) {
            log("Timed out, retrying...");
            beginTxn();
        }
    }

    private void onReadResult(TxnReadResultMsg msg) {
        log("READ RESULT (" + msg.key + ", " + msg.value + ")");

        // save the read value(s)
        if (msg.key.equals(firstKey)) firstValue = msg.value;
        if (msg.key.equals(secondKey)) secondValue = msg.value;

        boolean opDone = (firstValue != null && secondValue != null);

        // do we only read or also write?
        double writeRandom = r.nextDouble();
        boolean doWrite = writeRandom < WRITE_PROBABILITY;
        if (doWrite && opDone) writeTwo();

        // check if the transaction should end;
        // otherwise, read two again
        if (opDone) numOpDone++;
        if (numOpDone >= numOpTotal) {
            endTxn();
        } else if (opDone) {
            readTwo();
        }
    }

    private void onTxnResult(TxnResultMsg msg) throws InterruptedException {
        if (msg.commit) {
            numCommittedTxn++;
            log("COMMIT OK (" + numCommittedTxn + "/" + numAttemptedTxn + ")");
        } else {
            log("COMMIT FAIL (" + (numAttemptedTxn - numCommittedTxn) + "/" + numAttemptedTxn + ")");
        }

        /*
        try {
            System.out.println(">>> Press ENTER to continue <<<");
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }

        */

        //beginTxn();
    }

    void log(String s) {
        System.out.format("%2d: %s\n", clientId, s);
    }

}
