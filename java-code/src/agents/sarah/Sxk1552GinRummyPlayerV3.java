package agents.sarah;
//===============================================================================
// Name			    : agents.sarah.Sxk1552GinRummyPlayerV3.java
// Author		    : Sarah Kettell
//================================================================================

import ginrummy.Card;
import ginrummy.GinRummyPlayer;
import ginrummy.GinRummyUtil;

import java.util.ArrayList;
import java.util.Random;

public class Sxk1552GinRummyPlayerV3 implements GinRummyPlayer {
    private int playerNum;
    @SuppressWarnings("unused")
    private int startingPlayerNum;
    private ArrayList<Card> cards = new ArrayList<Card>();
    private Random random = new Random();
    private boolean opponentKnocked = false;
    private int refusedKnock = 0;
    private int checkAccess = 0;
    Card faceUpCard, drawnCard;
    ArrayList<Long> drawDiscardBitstrings = new ArrayList<Long>();

    @Override
    public void startGame(int playerNum, int startingPlayerNum, Card[] cards) {
        this.playerNum = playerNum;
        this.startingPlayerNum = startingPlayerNum;
        this.cards.clear();
        GameState.clearGameState();
        for (Card card : cards) {
            this.cards.add(card);
            GameState.seenCards = GameState.seenCards | 1L << card.getId();  // track seen cards in own hand
        }
        opponentKnocked = false;
        drawDiscardBitstrings.clear();
    }

    @Override
    public boolean willDrawFaceUpCard(Card card) {
        this.faceUpCard = card;
        @SuppressWarnings("unchecked")
        ArrayList<Card> newCards = (ArrayList<Card>) cards.clone();
        newCards.add(card);

        // track all seen face up cards
        GameState.seenCards = GameState.seenCards | 1L << card.getId();

        // Draw the face up card if it forms a meld that lowers deadwood after discard
        int deadwoodAfterDraw = Helper.doesCardLowerDeadwood(cards, card);
        for (ArrayList<Card> meld : GinRummyUtil.cardsToAllMelds(newCards)) {
            if (meld.contains(card) && deadwoodAfterDraw > 0) {
                return true;
            }
        }
        boolean isUnmeldable = Helper.getUnmeldableCardsAfterDraw(newCards).contains(card);
        return Helper.getDrawStrategy(deadwoodAfterDraw, Helper.getSetBits(GameState.seenCards), isUnmeldable);
    }

    @Override
    public void reportDraw(int playerNum, Card drawnCard) {
        // Ignore other player draws.  Add to cards if playerNum is this player.
        if (playerNum == this.playerNum) {
            cards.add(drawnCard);
            this.drawnCard = drawnCard;
            // add card to tracked seen cards
            GameState.seenCards = GameState.seenCards | 1L << drawnCard.getId();
            // decrease number of cards left in face down pile if face down drawn
            if(drawnCard != this.faceUpCard){
                GameState.numFaceDownCards--;
            }
        }
        // decrease number of cards left in face down pile when drawn from by other player
        if(playerNum != this.playerNum && drawnCard == null) {
            GameState.numFaceDownCards--;
        }
        // track cards the opponent has drawn from face up pile
        if(playerNum != this.playerNum && drawnCard != null){
            GameState.knownOpponentCards = GameState.knownOpponentCards | 1L << drawnCard.getId();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Card getDiscard() {
        // find a set of cards that can be discarded to result in minimal deadwood
        ArrayList<Card> candidateCards = Helper.getBestDiscardCards(cards);
        // only one card found, verify it is a valid discard
        if(candidateCards.size() == 1){
            // cannot discard this card, find one that is second best, otherwise discard it
            if (candidateCards.get(0) == drawnCard && drawnCard == faceUpCard) {
                int minDeadwood = Integer.MAX_VALUE;
                for (Card card : cards) {
                    // Cannot draw and discard face up card.
                    if (card == drawnCard && drawnCard == faceUpCard)
                        continue;

                    // get candidate cards that result in the minimum deadwood after discard
                    ArrayList<Card> remainingCards = (ArrayList<Card>) cards.clone();
                    remainingCards.remove(card);
                    ArrayList<ArrayList<ArrayList<Card>>> bestMeldSets = GinRummyUtil.cardsToBestMeldSets(remainingCards);
                    int deadwood = bestMeldSets.isEmpty() ? GinRummyUtil.getDeadwoodPoints(remainingCards) : GinRummyUtil.getDeadwoodPoints(bestMeldSets.get(0), remainingCards);
                    if (deadwood <= minDeadwood) {
                        if (deadwood < minDeadwood) {
                            minDeadwood = deadwood;
                            candidateCards.clear();
                        }
                        candidateCards.add(card);
                    }
                }
            }
        }
        else{
            if(candidateCards.contains(drawnCard) && drawnCard == faceUpCard){
                candidateCards.remove(drawnCard);
            }
        }
        // valid set of minimal deadwood candidate cards found
        // If candidate cards > 1, remove any that will make a meld for opponent
        long tempCandidateCards = GinRummyUtil.cardsToBitstring(candidateCards);
        int numCards = candidateCards.size();
        if(numCards > 1) {
            for (Card card : candidateCards) {
                if (Helper.canOpponentMakeMeld(card) && (tempCandidateCards & 1L << card.getId()) != 0) {
                    tempCandidateCards = tempCandidateCards ^ 1L << card.getId();
                    numCards--;
                }
                if(numCards == 1) break;
            }
            candidateCards = GinRummyUtil.bitstringToCards(tempCandidateCards);
        }
        // If candidate cards still > 1, remove any that will never make a meld
        ArrayList<Card> unmeldableCards2 = Helper.getUnmeldableCardsAfterTwoDraw(cards);
        numCards = candidateCards.size();
        if(numCards > 1){
            for (Card card : candidateCards) {
                if (unmeldableCards2.contains(card) && (tempCandidateCards & 1L << card.getId()) != 0) {
                    tempCandidateCards = tempCandidateCards ^ 1L << card.getId();
                    numCards--;
                }
                if(numCards == 1) break;
            }
            candidateCards = GinRummyUtil.bitstringToCards(tempCandidateCards);
        }
        // If candidate cards still > 1, remove any cards that won't make a meld after one draw
        ArrayList<Card> unmeldableCards1 = Helper.getUnmeldableCardsAfterDraw(cards);
        if(numCards > 1){
            for (Card card : candidateCards) {
                if (unmeldableCards1.contains(card) && (tempCandidateCards & 1L << card.getId()) != 0) {
                    tempCandidateCards = tempCandidateCards ^ 1L << card.getId();
                    numCards--;
                }
                if(numCards == 1) break;
            }
            candidateCards = GinRummyUtil.bitstringToCards(tempCandidateCards);
        }
        // If candidate cards still > 1, check if likely that opponent will discard tens or face cards,
        // increasing likelihood of picking up meld from face up
        if(numCards > 1){
            double avgDiscardRank = Helper.getAveragePointsForOpponentDiscards();
            if(avgDiscardRank == 10){
                for(Card card : candidateCards){
                    if(GinRummyUtil.getDeadwoodPoints(card) == 10){
                        tempCandidateCards = tempCandidateCards ^ 1L << card.getId();
                        numCards--;
                    }
                    if(numCards == 1) break;
                }
            }
        }

        // Pick random card from remaining candidate cards
        Card discard = candidateCards.get(random.nextInt(candidateCards.size()));

        return discard;
    }

    @Override
    public void reportDiscard(int playerNum, Card discardedCard) {
        // Ignore other player discards.  Remove from cards if playerNum is this player.
        if (playerNum == this.playerNum) {
            cards.remove(discardedCard);
            // end of turn, increment turn counter
            GameState.numTurns++;
        }
        // track the cards the opponent discards
        if (playerNum != this.playerNum){
            GameState.discardedOpponentCards = GameState.discardedOpponentCards | 1L << discardedCard.getId();
            // if discardedCard is known opponent card, remove
            if ((GameState.knownOpponentCards & 1L << discardedCard.getId()) != 0){
                GameState.knownOpponentCards = GameState.knownOpponentCards ^ 1L << discardedCard.getId();
            }
        }
    }

    @Override
    public ArrayList<ArrayList<Card>> getFinalMelds() {
        // Check if deadwood of maximal meld is low enough to go out.
        ArrayList<ArrayList<ArrayList<Card>>> bestMeldSets = GinRummyUtil.cardsToBestMeldSets(cards);
        if(!opponentKnocked) {
            int bestDeadwood = Helper.getBestDeadwood(this.cards);
            // can't knock or no melds
            if (bestMeldSets.isEmpty() || bestDeadwood > GinRummyUtil.MAX_DEADWOOD) {
                return null;
            }
            // Check if opponent can meld into deadwood cards, if so, try not to use that set
            if(bestMeldSets.size() > 1){
                ArrayList<ArrayList<Card>> deadwoodCards = Helper.getDeadwoodCards(cards, bestMeldSets);
                long leftoverCards = 0;
                for(int i = 0; i < deadwoodCards.size(); i++){
                    for(Card card : deadwoodCards.get(i)){
                        if(!Helper.canCardBeLaidOff(card) && (leftoverCards & 1L << card.getId()) == 0){
                            leftoverCards = leftoverCards | 1L << card.getId();
                        }
                    }
                    // if no leftover cards, cards can likely be melded, return meldset
                    if(leftoverCards == 0) {return bestMeldSets.remove(i);}
                    leftoverCards = 0;
                    if(bestMeldSets.size() == 1){break;}
                }
            }
            // if gin, knock
            if(bestDeadwood == 0){
                return bestMeldSets.get(random.nextInt(bestMeldSets.size()));
            }
            // follow nash eq. strategy for seen cards,unmatchable cards, and deadwood
            if(!Helper.getKnockStrategy(bestDeadwood, GameState.seenCards, Helper.getUnmeldableCardsAfterDraw(cards).size())){
                return null;
            }
        }
        return bestMeldSets.isEmpty() ? new ArrayList<ArrayList<Card>>() : bestMeldSets.get(random.nextInt(bestMeldSets.size()));
    }

    @Override
    public void reportFinalMelds(int playerNum, ArrayList<ArrayList<Card>> melds) {
        // Melds ignored by simple player, but could affect which melds to make for complex player.
        if (playerNum != this.playerNum)
            opponentKnocked = true;
    }

    @Override
    public void reportScores(int[] scores) {
        // Ignored by simple player, but could affect strategy of more complex player.
    }

    @Override
    public void reportLayoff(int playerNum, Card layoffCard, ArrayList<Card> opponentMeld) {
        // Ignored by simple player, but could affect strategy of more complex player.

    }

    @Override
    public void reportFinalHand(int playerNum, ArrayList<Card> hand) {
        // Ignored by simple player, but could affect strategy of more complex player.
    }

    // Class to hold the current state of the game, tracking cards
    static class GameState {
        final static int NUM_FACEDOWN_LEFT = Card.NUM_CARDS - 22; // remove 20 cards in hands, 2 leftover at end
        static int numFaceDownCards = NUM_FACEDOWN_LEFT;
        static long seenCards = 0;
        static long knownOpponentCards = 0;
        static long discardedOpponentCards = 0;
        static int numTurns = 0;

        static void clearGameState(){
            numFaceDownCards = NUM_FACEDOWN_LEFT;
            seenCards = 0;
            knownOpponentCards = 0;
            discardedOpponentCards = 0;
            numTurns = 0;
        }
    }

    // Helper class to calculate predictions based on game state
    static class Helper {
        // Determine the minimum amount of deadwood points a hand contains
        public static int getBestDeadwood(ArrayList<Card> myCards) {
            if(myCards.size() != 10) throw new IllegalArgumentException("Need 10 cards");
            ArrayList<ArrayList<ArrayList<Card>>> bestMeldConfigs = GinRummyUtil.cardsToBestMeldSets(myCards);
            return bestMeldConfigs.isEmpty() ? GinRummyUtil.getDeadwoodPoints(myCards) :
                    GinRummyUtil.getDeadwoodPoints(bestMeldConfigs.get(0), myCards);
        }

        // Determine minimum deadwood points possible after a discard
        public static int getDeadwoodAfterDiscard(ArrayList<Card> myCards) {
            if(myCards.size() != 11) throw new IllegalArgumentException("Need 11 cards");
            int bestDeadwoodPoints = Integer.MAX_VALUE;
            long myHand = GinRummyUtil.cardsToBitstring(myCards);
            ArrayList<Card> myHandCopy = (ArrayList<Card>) myCards.clone();
            for (Card card : myCards){
                //myHand = myHand ^ 1L << card.getId();    // produced error
                myHandCopy.remove(card);
                bestDeadwoodPoints = Math.min(bestDeadwoodPoints, getBestDeadwood(myHandCopy));
                myHandCopy.add(card);
                //myHand = myHand | 1L << card.getId();
            }
            return bestDeadwoodPoints;
        }

        // Determine how many cards would make gin given current hand
        public static int getNumCardsForGin(ArrayList<Card> myCards){
            if(myCards.size() != 10) throw new IllegalArgumentException("Need 10 cards");
            long myHand = GinRummyUtil.cardsToBitstring(myCards);
            int count = 0;
            for (int i = 0; i < 52; i++){
                Card c = Card.getCard(i);
                if ((1L << c.getId() & GameState.seenCards) == 0) {
                    if((myHand & 1L << c.getId()) == 0){
                        myHand = myHand | 1L << c.getId();
                        if (getDeadwoodAfterDiscard(GinRummyUtil.bitstringToCards(myHand)) == 0) {
                            count++;
                        }
                        myHand = myHand ^ 1L << c.getId();
                    }
                }
            }
            return count;
        }

        // Determine the collection of cards that produces minimum deadwood points after discard
        public static ArrayList<Card> determineBestCardsAfterDiscard(ArrayList<Card> myCards){      // maybe in the draw face up stage?
            if(myCards.size() != 11) throw new IllegalArgumentException("Need 11 cards");
            long myHand = GinRummyUtil.cardsToBitstring(myCards);
            for (Card card : myCards) {
                myHand = myHand ^ 1L << card.getId();
                if(getDeadwoodAfterDiscard(myCards) == getBestDeadwood(GinRummyUtil.bitstringToCards(myHand))){
                    return GinRummyUtil.bitstringToCards(myHand);
                }
                myHand = myHand | 1L << card.getId();
            }
            return null;
        }

        // Determine whether opponent can make a meld with a specific card
        public static boolean canOpponentMakeMeld(Card card){
            long cardsNotInOpponentHand = GameState.seenCards ^ GameState.knownOpponentCards ^ 1L << card.getId();
            int cardRank = card.getRank();
            int cardSuit = card.getSuit();
            int count = 0;
            // check how many cards are not available of same rank
            for(int i = 0; i < Card.NUM_SUITS; i++){
                if((cardsNotInOpponentHand & 1L << Card.getId(cardRank, i)) != 0) {
                    count++;
                }
            }
            if(count >= 2) {return false;}
            // if cards before and after are not available to create a run, return false
            if(cardRank - 1 >= 0 && ((cardsNotInOpponentHand & 1L << Card.getId(cardRank-1, cardSuit)) == 0)) {
                // card before is available, is card before that available?
                if(cardRank - 2 >= 0 && ((cardsNotInOpponentHand & 1L << Card.getId(cardRank-2, cardSuit)) == 0)) {
                    // yes, both cards before are available
                    return true;
                }
                // no, it was not available, is card after available?
                if(cardRank + 1 < Card.NUM_RANKS && ((cardsNotInOpponentHand & 1L << Card.getId(cardRank+1, cardSuit)) == 0)) {
                    // yes, card before and after are available, return true
                    return true;
                }
            }
            if(cardRank + 1 < Card.NUM_RANKS && ((cardsNotInOpponentHand & 1L << Card.getId(cardRank+1, cardSuit)) == 0)) {
                // card after is available, is card after that available?
                if(cardRank + 2 < Card.NUM_RANKS && ((cardsNotInOpponentHand & 1L << Card.getId(cardRank+2, cardSuit)) != 0)) {
                    // yes, both cards after are available
                    return true;
                }
            }
            return false;
        }

        // Determine if player can lay off discard card if opponent knocks
        // Note, this may work better against simple player because all known opponent cards MUST be picked up
        // because they melded, as Simple player only picks face up cards during melds
        public static boolean canCardBeLaidOff(Card card){
            long oppHand = GameState.knownOpponentCards;
            int cardID = card.getId();
            int cardRank = card.getRank();
            int cardSuit = card.getSuit();
            long cardBitStr = 1L << cardID;
            while (oppHand != 0){
                long temp = oppHand & (oppHand-1);
                long oppCard = oppHand ^ temp;
                if(GinRummyUtil.bitstringToCards(oppCard).get(0).getRank() == cardRank){
                    return true;
                }
                if(oppCard == 1L << cardID-1 || oppCard == 1L << cardID+1){
                    return true;
                }
                oppHand = temp;
            }
            return false;
        }

        // Determine whether a given card makes a productive new meld in player's hands
        public static int doesCardLowerDeadwood(ArrayList<Card> myCards, Card card){
            if(myCards.size() != 10) throw new IllegalArgumentException("Need 10 cards");
            int currentBestDeadwood = getBestDeadwood(myCards);
            myCards.add(card);
            int newBestDeadwood = getDeadwoodAfterDiscard(myCards);
            myCards.remove(card);
            return currentBestDeadwood - newBestDeadwood;
        }

        // Determine which cards in hand cannot be made into melds with next draw
        public static ArrayList<Card> getUnmeldableCardsAfterDraw(ArrayList<Card> myCards){
            // find cards not already melded
            long leftoverCards = GinRummyUtil.cardsToBitstring(myCards);
            for (ArrayList<Card> meld : GinRummyUtil.cardsToAllMelds(myCards)) {
                for(Card card : myCards){
                    if (meld.contains(card) && (leftoverCards & 1L << card.getId()) != 0){
                        leftoverCards = leftoverCards ^ 1L << card.getId();
                    }
                }
            }
            if(leftoverCards != 0){
                long tempLeftoverCards = leftoverCards;
                // for each card leftover, remove if it can be melded by adding just one unseen card
                for(Card leftoverCard : GinRummyUtil.bitstringToCards(leftoverCards)){
                    for (int i = 0; i < 52; i++){
                        Card c = Card.getCard(i);
                        if ((1L << c.getId() & GameState.seenCards) == 0) {
                            myCards.add(c);
                            for (ArrayList<Card> meld : GinRummyUtil.cardsToAllMelds(myCards)) {
                                if (meld.contains(leftoverCard) && (tempLeftoverCards & 1L << leftoverCard.getId()) != 0) {
                                    tempLeftoverCards = tempLeftoverCards ^ 1L << leftoverCard.getId();
                                }
                            }
                            myCards.remove(c);
                        }
                    }
                }
                leftoverCards = tempLeftoverCards;
            }
            return GinRummyUtil.bitstringToCards(leftoverCards);
        }

        // Determine which cards in hand cannot be made into meld with 2 more draws
        public static ArrayList<Card> getUnmeldableCardsAfterTwoDraw(ArrayList<Card> myCards){
            // find cards not already melded
            long leftoverCards = GinRummyUtil.cardsToBitstring(myCards);
            for (ArrayList<Card> meld : GinRummyUtil.cardsToAllMelds(myCards)) {
                for(Card card : myCards){
                    if (meld.contains(card) && (leftoverCards & 1L << card.getId()) != 0){
                        leftoverCards = leftoverCards ^ 1L << card.getId();
                    }
                }
            }
            // get bitstring of unseen cards combined with leftover cards
            long availableCardsBitStr = 0;
            for (int i = 0; i < 52; i++){
                Card c = Card.getCard(i);
                if ((1L << c.getId() & GameState.seenCards) == 0 || (1L << c.getId() & leftoverCards) != 0 ) {
                    availableCardsBitStr = availableCardsBitStr | 1L << c.getId();
                }
            }
            // get all melds for the combination of unseen cards and leftover unmelded cards
            // check if any of the leftover cards are in it, if so, remove them from unmeldable set
            for (ArrayList<Card> meld : GinRummyUtil.cardsToAllMelds(GinRummyUtil.bitstringToCards(availableCardsBitStr))){
                for (Card leftoverCard : GinRummyUtil.bitstringToCards(leftoverCards)){
                    if (meld.contains(leftoverCard) && (leftoverCards & 1L << leftoverCard.getId()) != 0) {
                        leftoverCards = leftoverCards ^ 1L << leftoverCard.getId();
                    }
                }
            }
            return GinRummyUtil.bitstringToCards(leftoverCards);
        }

        // Determine average deadwood in hand after drawing next card
        public static int getAvgDeadwoodAfterDraw(ArrayList<Card> myCards){
            if(myCards.size() != 10) throw new IllegalArgumentException("Need 10 cards");
            int totalDeadwood = 0;
            int numCards = 0;
            // for each unseen card, add to hand and get best deadwood
            for (int i = 0; i < 52; i++){
                Card c = Card.getCard(i);
                if ((1L << c.getId() & GameState.seenCards) == 0) {
                    myCards.add(c);
                    totalDeadwood += getDeadwoodAfterDiscard(myCards);
                    numCards++;
                    myCards.remove(c);
                }
            }
            return totalDeadwood/numCards;
        }

        // Determine a set of cards that can be discarded to result in minimal deadwood
        public static ArrayList<Card> getBestDiscardCards(ArrayList<Card> myCards){
            if(myCards.size() != 11) throw new IllegalArgumentException("Need 11 cards");
            // get the minimum deadwood after discarding some set of cards
            int minDeadwood = getDeadwoodAfterDiscard(myCards);
            long discardCards = 0;
            long myHand = GinRummyUtil.cardsToBitstring(myCards);
            // check which cards may have been discarded, if minimum deadwood reached, end hand should not contain card
            for(Card card : myCards){
                myHand = myHand ^ 1L << card.getId();
                // best deadwood reached with this hand, card should be discarded
                if(minDeadwood == getBestDeadwood(GinRummyUtil.bitstringToCards(myHand))) {
                    discardCards = discardCards | 1L << card.getId();
                }
                myHand = myHand | 1L << card.getId();
            }
            return GinRummyUtil.bitstringToCards(discardCards);
        }

        // is opponent discarding high or low cards more frequently
        public static double getAveragePointsForOpponentDiscards(){
            int totalPoints = 0;
            int numCards = 0;
            long tmp = GameState.discardedOpponentCards;
            while (tmp != 0){
                long tmp1 = tmp & (tmp-1);
                long card = tmp ^ tmp1;
                totalPoints += GinRummyUtil.getDeadwoodPoints(GinRummyUtil.bitstringToCards(card));
                numCards++;
                tmp = tmp1;
            }
            return numCards > 0 ? totalPoints/(double)numCards : 0;
        }

        // get the deadwood cards for each meld
        public static ArrayList<ArrayList<Card>> getDeadwoodCards(ArrayList<Card> myCards, ArrayList<ArrayList<ArrayList<Card>>> myMelds){
            ArrayList<ArrayList<Card>> deadwoodCards = new ArrayList<>();
            long myCardsBitStr = GinRummyUtil.cardsToBitstring(myCards);
            long tempDeadwood = myCardsBitStr;
            for(ArrayList<ArrayList<Card>> meldSet : myMelds){
                for(Card card : myCards){
                    for(ArrayList<Card> meld : meldSet){
                        if(meld.contains(card) && (tempDeadwood & 1L << card.getId()) != 0){
                            tempDeadwood = tempDeadwood ^ 1L << card.getId();
                        }
                    }
                }
                deadwoodCards.add(GinRummyUtil.bitstringToCards(tempDeadwood));
                tempDeadwood = myCardsBitStr;
            }
            return deadwoodCards;
        }

        // Number of cards in a bit string, taken from CFR program
        /**
         * Efficiently Count # of 1's in a bitstring
         *
         * @param bitString the bitstring
         * @return the number of set bits (i.e. bits set to 1) in the bitstring
         */
        public static int getSetBits(long bitString) {
            // Using Kernighan�s Algorithm for counting set bits
            int count = 0;
            while (bitString != 0) {
                bitString = bitString & (bitString - 1); // Unset the least significant bit with a 1
                count++;
            }
            return count;
        }

        // Knock Nash Equil. strategy based on seen cards / 5, improvement, and if the card is unmatchable
        public static boolean getDrawStrategy(int improvement, long seen, boolean meldable){
            int meldableIndex = 0;
            if(meldable){meldableIndex = 1;}
            // strategy[deadwood-1][seen/5 - 2][meldable]
            int[][][] strategy = {
                    // 2     3     4     5     6     7     8         = number seen/3
                    {{0,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 0
                    {{0,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 1
                    {{1,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 2
                    {{1,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 3
                    {{0,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 4
                    {{0,0},{1,0},{1,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 5
                    {{0,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 6
                    {{0,0},{1,0},{1,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 7
                    {{0,0},{1,0},{1,0},{0,0},{0,0},{0,0},{0,0}},   // improvement = 8
                    {{0,0},{1,0},{1,0},{0,0},{0,0},{0,0},{0,0}}    // improvement = 9
            };
            int numSeenKey = getSetBits(seen)/5;
            if(numSeenKey < 2 || improvement > 9 || numSeenKey > 8){
                // seen cannot be below 2
                // if numSeenKey > 8 or improvement > 9, no eq. strat for this, revert back to deadwood strat.
                return false;
            }
            Random random = new Random();
            if(strategy[improvement-1][numSeenKey-2][meldableIndex] == 1){
                return true;
            }
            return false;
        }

        // Knock Nash Equil. strategy based on seen cards / 5 and deadwood
        public static boolean getKnockStrategy(int deadwood, long seen, int unmatchable){
            // strategy[deadwood-1][seen/5 - 2]
            int[][][] strategy = {
                    //   2        3          4         5        6         7          8         = number seen/3
                    {{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0}},   // deadwood = 1
                    {{1,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,1,0},{0,0,0,0},{0,0,0,0}},   // deadwood = 2
                    {{1,0,1,0},{0,1,0,0},{0,0,1,0},{0,1,1,0},{0,0,1,0},{0,0,1,0},{0,0,0,0}},   // deadwood = 3
                    {{1,1,1,0},{1,1,0,0},{0,1,1,0},{0,1,1,0},{0,0,1,1},{0,0,0,0},{0,0,0,0}},   // deadwood = 4
                    {{1,1,1,0},{1,1,1,0},{0,1,1,0},{0,0,1,1},{0,1,0,0},{0,0,0,0},{0,0,0,0}},   // deadwood = 5
                    {{1,1,1,1},{1,1,1,1},{1,1,1,1},{0,0,1,1},{0,0,1,1},{0,0,0,0},{0,0,0,0}},   // deadwood = 6
                    {{1,1,1,1},{1,1,1,1},{0,1,1,1},{0,0,1,1},{0,0,0,0},{0,0,0,0},{0,0,0,0}},    // deadwood = 7
                    {{1,1,1,1},{1,1,1,1},{0,1,1,1},{0,0,1,1},{0,0,0,0},{0,0,0,0},{0,0,0,0}},    // deadwood = 8
                    {{1,1,1,1},{1,1,1,1},{0,1,1,1},{0,0,1,1},{0,0,0,0},{0,0,0,0},{0,0,0,0}},    // deadwood = 9
                    {{1,1,1,1},{1,1,1,1},{0,0,1,1},{0,0,0,1},{0,0,0,0},{0,0,0,0},{0,0,0,0}}     // deadwood = 10
            };
            int numSeenKey = getSetBits(seen)/5;
            if(numSeenKey < 2 || deadwood > 10 || numSeenKey > 8 || unmatchable > 3){
                // seen cannot be below 2 and deadwood cannot be above 10
                // if numSeenKey > 8 or unmatchable > 3, no eq. strat for this, revert back to deadwood strat.
                return false;
            }
            Random random = new Random();
            if(strategy[deadwood-1][numSeenKey-2][unmatchable] == 1){
                return true;
            }
            return false;
        }

    }


}
