package fr.ses10doigts.tradeIO5.service.tool;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;

public class DataBaseAccess {
    @Autowired
    private EntityManager entityManager;

    public static void excludeDisabledItems( boolean exclude ){
        EntityManager em = SpringContextHolder.getBean(EntityManager.class);
        Session session = em.unwrap(Session.class);

        if (!exclude) {
            session.enableFilter("enabledFilter").setParameter("isEnabled", true);
        } else {
            session.disableFilter("enabledFilter");
        }
    }

}
