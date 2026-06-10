package com.taverntales.domain.npc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * NPC 状态 JPA Repository。
 * 当前只有 CRUD 基本方法，后续按需加自定义查询。
 */
@Repository
public interface NpcStateRepository extends JpaRepository<NpcState, String> {
}
