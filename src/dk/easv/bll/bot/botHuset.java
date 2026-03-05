package dk.easv.bll.bot;

import dk.easv.bll.game.GameState;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.field.IField;
import dk.easv.bll.move.IMove;
import dk.easv.bll.move.Move;

import java.util.*;

/**
 * MCTS-bot i en fil, uden tråde, uden I/O.
 */
public class botHuset implements IBot {

    private static final String BOTNAME = "botHuset";
    private static final int TIME_LIMIT_MS = 900; // lidt under 1 sek for sikkerhed
    private static final double EXPLORATION = Math.sqrt(2);

    private final Random rand = new Random();

    @Override
    public IMove doMove(IGameState state) {
        long endTime = System.currentTimeMillis() + TIME_LIMIT_MS;

        // Hvem er vi? 0 eller 1
        int myPlayer = state.getMoveNumber() % 2;

        // Root-simulator baseret på nuværende state
        GameSimulator rootSim = new GameSimulator(new GameState(state));
        rootSim.setCurrentPlayer(myPlayer);

        Node root = new Node(null, null, rootSim, -1); // movePlayer = -1 for root

        // Init untried moves for root
        root.untriedMoves.addAll(rootSim.getCurrentState().getField().getAvailableMoves());

        int iterations = 0;
        while (System.currentTimeMillis() < endTime) {
            iterations++;

            // 1) Selection
            Node node = root;
            GameSimulator sim = node.simulator.copy();

            while (node.untriedMoves.isEmpty() && !sim.isTerminal() && !node.children.isEmpty()) {
                node = selectUCT(node);
                sim = node.simulator.copy();
            }

            // 2) Expansion
            if (!sim.isTerminal() && !node.untriedMoves.isEmpty()) {
                IMove m = node.untriedMoves.remove(rand.nextInt(node.untriedMoves.size()));
                GameSimulator childSim = sim.copy();
                childSim.updateGame(m);
                Node child = new Node(node, m, childSim, childSim.getLastPlayer());
                child.untriedMoves.addAll(childSim.getCurrentState().getField().getAvailableMoves());
                node.children.add(child);
                node = child;
                sim = childSim;
            }

            // 3) Simulation (rollout)
            GameSimulator rolloutSim = sim.copy();
            while (!rolloutSim.isTerminal()) {
                List<IMove> moves = rolloutSim.getCurrentState().getField().getAvailableMoves();
                if (moves.isEmpty()) break;
                IMove m = moves.get(rand.nextInt(moves.size()));
                rolloutSim.updateGame(m);
            }

            // 4) Backpropagation
            int result = rolloutSim.getResultForPlayer(myPlayer); // 1 win, 0 draw, -1 loss
            Node cur = node;
            while (cur != null) {
                cur.visits++;
                cur.wins += (result == 1 ? 1.0 : (result == 0 ? 0.5 : 0.0));
                cur = cur.parent;
            }
        }

        // Vælg det barn med flest besøg
        if (root.children.isEmpty()) {
            // fallback: random lovligt træk
            List<IMove> moves = state.getField().getAvailableMoves();
            return moves.get(rand.nextInt(moves.size()));
        }

        Node best = Collections.max(root.children, Comparator.comparingInt(n -> n.visits));
        return best.move;
    }

    @Override
    public String getBotName() {
        return BOTNAME;
    }

    // ---------- MCTS Node ----------

    private static class Node {
        final Node parent;
        final IMove move;
        final GameSimulator simulator;
        final int movePlayer; // hvem lavede 'move'
        final List<Node> children = new ArrayList<>();
        final List<IMove> untriedMoves = new ArrayList<>();
        int visits = 0;
        double wins = 0.0;

        Node(Node parent, IMove move, GameSimulator simulator, int movePlayer) {
            this.parent = parent;
            this.move = move;
            this.simulator = simulator;
            this.movePlayer = movePlayer;
        }
    }

    private Node selectUCT(Node node) {
        return Collections.max(node.children, (a, b) -> {
            double ua = uctValue(a);
            double ub = uctValue(b);
            return Double.compare(ua, ub);
        });
    }

    private double uctValue(Node n) {
        if (n.visits == 0) return Double.POSITIVE_INFINITY;
        double exploit = n.wins / n.visits;
        double explore = EXPLORATION * Math.sqrt(Math.log(n.parent.visits + 1.0) / n.visits);
        return exploit + explore;
    }

    // ---------- Lokal GameSimulator (kopi af regler fra GameManager/ExampleSneakyBot) ----------

    private enum GameOverState {
        Active,
        Win,
        Tie
    }

    private static class GameSimulator {
        private final GameState state;
        private int currentPlayer; // 0 eller 1
        private GameOverState gameOver = GameOverState.Active;
        private int lastPlayer = -1;

        GameSimulator(GameState state) {
            this.state = state;
            this.currentPlayer = state.getMoveNumber() % 2;
        }

        GameSimulator(GameState state, int currentPlayer, GameOverState go, int lastPlayer) {
            this.state = state;
            this.currentPlayer = currentPlayer;
            this.gameOver = go;
            this.lastPlayer = lastPlayer;
        }

        GameSimulator copy() {
            GameState copyState = new GameState(state);
            return new GameSimulator(copyState, currentPlayer, gameOver, lastPlayer);
        }

        void setCurrentPlayer(int p) {
            this.currentPlayer = p;
        }

        IGameState getCurrentState() {
            return state;
        }

        boolean isTerminal() {
            return gameOver != GameOverState.Active ||
                    state.getField().getAvailableMoves().isEmpty();
        }

        int getLastPlayer() {
            return lastPlayer;
        }

        /**
         * @return true hvis trækket var lovligt og udført.
         */
        boolean updateGame(IMove move) {
            if (!verifyMoveLegality(move)) return false;
            updateBoard(move);
            lastPlayer = currentPlayer;
            currentPlayer = (currentPlayer + 1) % 2;
            return true;
        }

        private boolean verifyMoveLegality(IMove move) {
            IField field = state.getField();
            boolean isValid = field.isInActiveMicroboard(move.getX(), move.getY());

            if (isValid && (move.getX() < 0 || 9 <= move.getX())) isValid = false;
            if (isValid && (move.getY() < 0 || 9 <= move.getY())) isValid = false;

            if (isValid && !field.getBoard()[move.getX()][move.getY()].equals(IField.EMPTY_FIELD))
                isValid = false;

            return isValid;
        }

        private void updateBoard(IMove move) {
            String[][] board = state.getField().getBoard();
            board[move.getX()][move.getY()] = currentPlayer + "";
            state.setMoveNumber(state.getMoveNumber() + 1);
            if (state.getMoveNumber() % 2 == 0) {
                state.setRoundNumber(state.getRoundNumber() + 1);
            }
            checkAndUpdateIfWin(move);
            updateMacroboard(move);
        }

        private void checkAndUpdateIfWin(IMove move) {
            String[][] macroBoard = state.getField().getMacroboard();
            int macroX = move.getX() / 3;
            int macroY = move.getY() / 3;

            if (macroBoard[macroX][macroY].equals(IField.EMPTY_FIELD) ||
                    macroBoard[macroX][macroY].equals(IField.AVAILABLE_FIELD)) {

                String[][] board = state.getField().getBoard();

                if (isWin(board, move, "" + currentPlayer))
                    macroBoard[macroX][macroY] = currentPlayer + "";
                else if (isTie(board, move))
                    macroBoard[macroX][macroY] = "TIE";

                //Check macro win
                if (isWin(macroBoard, new Move(macroX, macroY), "" + currentPlayer))
                    gameOver = GameOverState.Win;
                else if (isTie(macroBoard, new Move(macroX, macroY)))
                    gameOver = GameOverState.Tie;
            }
        }

        private boolean isTie(String[][] board, IMove move) {
            int localX = move.getX() % 3;
            int localY = move.getY() % 3;
            int startX = move.getX() - (localX);
            int startY = move.getY() - (localY);

            for (int i = startX; i < startX + 3; i++) {
                for (int k = startY; k < startY + 3; k++) {
                    if (board[i][k].equals(IField.AVAILABLE_FIELD) ||
                            board[i][k].equals(IField.EMPTY_FIELD))
                        return false;
                }
            }
            return true;
        }

        private boolean isWin(String[][] board, IMove move, String currentPlayer) {
            int localX = move.getX() % 3;
            int localY = move.getY() % 3;
            int startX = move.getX() - (localX);
            int startY = move.getY() - (localY);

            //check col
            for (int i = startY; i < startY + 3; i++) {
                if (!board[move.getX()][i].equals(currentPlayer))
                    break;
                if (i == startY + 3 - 1) return true;
            }

            //check row
            for (int i = startX; i < startX + 3; i++) {
                if (!board[i][move.getY()].equals(currentPlayer))
                    break;
                if (i == startX + 3 - 1) return true;
            }

            //check diagonal
            if (localX == localY) {
                int y = startY;
                for (int i = startX; i < startX + 3; i++) {
                    if (!board[i][y++].equals(currentPlayer))
                        break;
                    if (i == startX + 3 - 1) return true;
                }
            }

            //check anti diagonal
            if (localX + localY == 3 - 1) {
                int less = 0;
                for (int i = startX; i < startX + 3; i++) {
                    if (!board[i][(startY + 2) - less++].equals(currentPlayer))
                        break;
                    if (i == startX + 3 - 1) return true;
                }
            }
            return false;
        }

        private void updateMacroboard(IMove move) {
            String[][] macroBoard = state.getField().getMacroboard();
            for (int i = 0; i < macroBoard.length; i++)
                for (int k = 0; k < macroBoard[i].length; k++) {
                    if (macroBoard[i][k].equals(IField.AVAILABLE_FIELD))
                        macroBoard[i][k] = IField.EMPTY_FIELD;
                }

            int xTrans = move.getX() % 3;
            int yTrans = move.getY() % 3;

            if (macroBoard[xTrans][yTrans].equals(IField.EMPTY_FIELD))
                macroBoard[xTrans][yTrans] = IField.AVAILABLE_FIELD;
            else {
                // Field is already won, set all fields not won to avail.
                for (int i = 0; i < macroBoard.length; i++)
                    for (int k = 0; k < macroBoard[i].length; k++) {
                        if (macroBoard[i][k].equals(IField.EMPTY_FIELD))
                            macroBoard[i][k] = IField.AVAILABLE_FIELD;
                    }
            }
        }

        /**
         * @return 1 hvis myPlayer vinder, 0 ved remis, -1 ved tab
         */
        int getResultForPlayer(int myPlayer) {
            if (gameOver == GameOverState.Tie) return 0;
            if (gameOver == GameOverState.Active) return 0;
            // gameOver == Win, lastPlayer er vinderen
            if (lastPlayer == myPlayer) return 1;
            return -1;
        }
    }
}