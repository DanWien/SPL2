package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;
    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    /**
     * This array stores the players' threads.
     */
    private Thread[] plThreads;
    /**
     * This indicates the sleep time of the dealer thread
     */
    private boolean needNewCards = true;
    private long sleepTime = 10;

    private Thread dealerThread;
    private Object dealerLock = new Object();

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        plThreads = new Thread[players.length];

    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < players.length; i++) {
            Thread curr = players[i].createThread();
            plThreads[i] = curr;
            curr.start();
        }
        while (!shouldFinish()) {
            shuffleTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate(); //check if needed
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        int i = 0;
        for (Player p : players) {
            p.release();
            p.terminate();
            try {
                plThreads[i].join();
            } catch (InterruptedException e) {
            }
            i++;
        }
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (!table.setQueue.isEmpty()) {
            int id = table.setQueue.poll();
            int[] playerSlots = players[id].getTokens();
            if (players[id].realSet()) {
                int[] setToCheck = {table.slotToCard[playerSlots[0]], table.slotToCard[playerSlots[1]], table.slotToCard[playerSlots[2]]};
                boolean isLegalSet = env.util.testSet(setToCheck);
                if (isLegalSet) {
                    for (int i = 0; i < playerSlots.length; i++) {
                        for (int j = 0; j < env.config.players; j++) {
                            if (table.slotToPlayer[j][playerSlots[i]])
                                players[j].removeToken(playerSlots[i]);
                        }
                        table.removeCard(playerSlots[i]);
                    }
                    needNewCards = true;
                    updateTimerDisplay(true);
                    players[id].setPenalty(1);
                } else {
                    players[id].setPenalty(3);
                }
            }
            players[id].release();
        }
    }

    private void shuffleTable() {
        synchronized (table) {
            Random cardRandom = new Random();
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < env.config.tableSize; i++)
                slots.add(i);
            Collections.shuffle(slots);
            for (int i = 0; i < min(env.config.tableSize, deck.size()); i++) {
                int cardIdx = cardRandom.nextInt(deck.size());
                table.placeCard(deck.remove(cardIdx), slots.get(i));
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if (deck.size() > 0) {
            if (needNewCards) {
                Random cardRandom = new Random();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < env.config.tableSize; i++) {
                    if (table.slotToCard[i] == null)
                        slots.add(i);
                }
                Collections.shuffle(slots);
                for (int i = 0; i < slots.size(); i++) {
                    int cardIdx = cardRandom.nextInt(deck.size());
                    table.placeCard(deck.remove(cardIdx), slots.get(i));
                }
                needNewCards = false;
            }
        }
        else {
            List<Integer> cardsLeft = new ArrayList<>();
            for(int i = 0 ; i < env.config.tableSize ; i++) {
                Integer card = table.slotToCard[i];
                if (card != null)
                    cardsLeft.add(card);
            }
            if(env.util.findSets(cardsLeft,1).size() == 0)
                terminate();
        }
        //notifyAll();
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (dealerLock) {
            try {
                dealerLock.wait(50);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        } else {
            if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
                env.ui.setCountdown(max(0, reshuffleTime - System.currentTimeMillis()), true);
            } else
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized (table) {
            env.ui.removeTokens();
            for (int i = 0; i < players.length; i++) {
                players[i].removeTokens();
            }
            for (int i = 0; i < env.config.tableSize; i++) {
                Integer card = table.slotToCard[i];
                if (card != null) {
                    table.removeCard(i);
                    deck.add(card);
                }
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int winningScore = 0;
        for (int i = 0; i < players.length; i++) {
            if (players[i].getScore() > winningScore)
                winningScore = players[i].getScore();
        }
        List<Integer> winners = new ArrayList<>();
        for (int i = 0; i < players.length; i++) {
            if (players[i].getScore() == winningScore)
                winners.add(players[i].id);
        }
        int[] winningPlayers = new int[winners.size()];
        for (int i = 0; i < winningPlayers.length; i++)
            winningPlayers[i] = winners.get(i);
        env.ui.announceWinner(winningPlayers);
    }

    public void notifyDealer() {
        synchronized (dealerLock) {
            dealerLock.notifyAll();
        }
    }
}
