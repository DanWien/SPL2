package bguspl.set.ex;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * This array indicates what slots the player chose to put his tokens.
     */
    private int[] tokens;
    /**
     * Number of tokens the player has placed so far.
     */
    private int numOfTokens = 0;
    /**
     * This queue holds the player's key presses by order of FIFO.
     */
    private Queue<Integer> keyQueue;
    private int penalty = 0;
    private boolean sleepNow = false; // idea to make them sleep while cards are dealt.

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        tokens = new int[3];
        for (int i = 0; i < 3; i++)
            tokens[i] = -1;
        keyQueue = new ArrayBlockingQueue<>(3);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            if (penalty == 1) {
                penalty = 0;
                point();
            } else if (penalty == 3) {
                penalty = 0;
                penalty();
            }
            //if(sleepNow) {
            //    try {wait();}
            //    catch (InterruptedException ignored) {};
            //}
            while (!keyQueue.isEmpty() & numOfTokens <= 3) {
                int currSlot = keyQueue.poll();
                boolean exists = false;
                for (int i = 0; i < 3; i++) {
                    if (tokens[i] != -1 && tokens[i] == currSlot)
                        exists = true;
                }
                if (exists)
                    removeToken(currSlot);
                else if (numOfTokens < 3) {
                    placeToken(currSlot);
                    if (numOfTokens == 3) {
                        table.setQueue.add(id);
                    }
                }
            }
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                try {
                    synchronized (this) {
                        wait();
                    }
                } catch (InterruptedException ignored) {
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (table.slotToCard[slot] != null && keyQueue.size() < 3) // can later replace with try/catch
            keyQueue.add(slot);
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score++;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, score);
        try {
            env.ui.setFreeze(id, 1000);
            playerThread.sleep(1000);
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException e) {
        }
        ;
        keyQueue.clear();

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        for (int i = 3; i > 0; i--) {
            env.ui.setFreeze(id, i * 1000);
            try {
                playerThread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
        env.ui.setFreeze(id, 0);
        keyQueue.clear();
    }

    public int getScore() {
        return score;
    }

    public void removeTokens() {
        for (int i = 0; i < 3; i++) {
            table.removeToken(id, i);
            tokens[i] = -1;
        }
        numOfTokens = 0;
    }

    public void removeToken(int slot) {
        if (numOfTokens > 0) {
            table.removeToken(id, slot);
            for (int i = 0; i < 3; i++) {
                if (tokens[i] != -1 && tokens[i] == slot)
                    tokens[i] = -1;
            }
            numOfTokens--;
        }
    }

    public void placeToken(int slot) {
        if (numOfTokens < 3) {
            table.placeToken(id, slot);
            int i = 0;
            while (tokens[i] != -1)
                i++;
            tokens[i] = slot;
            numOfTokens++;
        }
    }

    public Thread createThread() {
        playerThread = new Thread(this, "player" + id);
        return playerThread;
    }

    public int[] getTokens() {
        int[] copy = new int[3];
        for (int i = 0; i < copy.length; i++)
            copy[i] = tokens[i];
        return copy;
    }

    public void setPenalty(int i) {
        penalty = i;
    }
    public void setWait(boolean b){
        sleepNow = b;
    }

    public void setTerminate () {
        terminate = true;
    }
}
