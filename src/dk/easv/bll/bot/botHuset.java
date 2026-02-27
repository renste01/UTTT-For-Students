package dk.easv.bll.bot;

import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;

import java.util.List;
import java.util.Random;

public class botHuset implements IBot
{
    private Random rand = new Random();
    private static final String BOTNAME = "botHuset";

    @Override
    public IMove doMove(IGameState state)
    {
        List<IMove> moves = state.getField().getAvailableMoves();
        if (moves.size() > 0)
        {
            return moves.get(rand.nextInt(moves.size()));
        }
        return null;
    }

    @Override
    public String getBotName()
    {
        return BOTNAME;
    }
}
