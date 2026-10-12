package server;

import chat.ClientCallbackPrx;
import chat.InvalidNameException;
import chat.Lobby;
import chat.NicknameTakenException;
import chat.SessionPrx;
import com.zeroc.Ice.Current;

/** Servant del objeto bien conocido "Lobby": punto de entrada de los clientes. */
public final class LobbyI implements Lobby {

    private final SessionManager sessions;

    public LobbyI(SessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public SessionPrx login(String nickname, ClientCallbackPrx callback, Current current)
            throws NicknameTakenException, InvalidNameException {
        return sessions.login(nickname, callback, current);
    }
}
