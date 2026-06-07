package com.taverntales.domain.player;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 玩家 Repository。
 *
 * 后续接入 Redis 缓存时，可在 Service 层加 @Cacheable，不修改此接口。
 */
@Repository
public interface PlayerRepository extends JpaRepository<Player, String> {

    /** 查询当前在线玩家 */
    List<Player> findByOnlineTrue();
}
