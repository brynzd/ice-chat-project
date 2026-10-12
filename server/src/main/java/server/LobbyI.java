package server;

import chat.ClientCallbackPrx;
import chat.InvalidNameException;
import chat.Lobby;
import chat.NicknameTakenException;
import chat.Session;
import chat.SessionPrx;
import com.zeroc.Ice.Current;

import java.util.function.Function;

/** Servant del objeto bien conocido "Lobby": punto de entrada de los clientes. */
public final class LobbyI implements Lobby {

    private final SessionManager sessions;
    private final Function<UserSession, Session> servantFor;

    /** servantFor crea el servant Session de cada usuario que inicia sesion. */
    public LobbyI(SessionManager sessions, Function<UserSession, Session> servantFor) {
        this.sessions = sessions;
        this.servantFor = servantFor;
    }

    @Override
    public SessionPrx login(String nickname, ClientCallbackPrx callback, Current current)
            throws NicknameTakenException, InvalidNameException {
        return sessions.login(nickname, callback, current, servantFor);
    }
}
