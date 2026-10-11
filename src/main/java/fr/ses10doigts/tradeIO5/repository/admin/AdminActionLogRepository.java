package fr.ses10doigts.tradeIO5.repository.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Journal admin en ajout seul : volontairement SANS {@code JpaRepository} (aucune suppression ni mise à jour exposée). */
public interface AdminActionLogRepository extends Repository<AdminActionLog, Long> {

    <S extends AdminActionLog> S save(S entry);

    /** Du plus récent au plus ancien. */
    Page<AdminActionLog> findAllByOrderByIdDesc(Pageable pageable);
}
