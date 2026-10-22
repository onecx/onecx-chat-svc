package org.tkit.onecx.chat.rs.v1;

import static jakarta.transaction.Transactional.TxType.NOT_SUPPORTED;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;
import org.tkit.quarkus.context.ApplicationContext;

import gen.org.tkit.onecx.chat.rs.v1.ChatApi;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
@Transactional(value = NOT_SUPPORTED)
public class ChatsV1Controller implements ChatApi {

    @Inject
    ChatDAO dao;

    @Inject
    ChatMapper mapper;

    @Override
    public Response getChatForUser(String chatId) {
        var principal = ApplicationContext.get().getPrincipal();
        // the tkit principal resolver never returns a blank name (blank values fall through to the
        // default principal, which is unset), so a null check is enough to fail closed
        if (principal == null) {
            return Response.status(NOT_FOUND).build();
        }
        // null if the chat does not exist, belongs to another tenant or the principal is neither
        // owner nor participant; 404 in all cases so callers cannot probe for existence
        var chat = dao.findChatForUser(chatId, principal);
        if (chat == null) {
            return Response.status(NOT_FOUND).build();
        }
        return Response.ok(mapper.mapChatV1(chat)).build();
    }
}
