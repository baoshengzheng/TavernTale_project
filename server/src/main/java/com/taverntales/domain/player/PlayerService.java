package com.taverntales.domain.player;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 玩家业务逻辑。
 *
 * 职责：玩家进入/离开、位置更新、在线状态管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;

    /**
     * 玩家进入酒馆。
     * 如果玩家不存在则创建新角色，已存在则恢复位置并设为在线。
     *
     * @param playerId 玩家 ID（由前端生成并记住，暂不做认证）
     * @param name 玩家显示名称
     * @param defaultRoomId 默认出生房间
     * @param defaultX 默认 x 坐标
     * @param defaultY 默认 y 坐标
     * @return 玩家实体
     */
    @Transactional
    public Player enterTavern(String playerId, String name, String defaultRoomId, int defaultX, int defaultY) {
        Player player = playerRepository.findById(playerId).orElseGet(() -> {
            log.info("新玩家 [{}] {} 进入酒馆", playerId, name);
            return Player.builder()
                    .id(playerId)
                    .name(name)
                    .currentRoomId(defaultRoomId)
                    .x(defaultX)
                    .y(defaultY)
                    .build();
        });

        player.setOnline(true);
        player.setLastActiveAt(LocalDateTime.now());
        player = playerRepository.save(player);

        log.debug("玩家 [{}] 已上线，位置: {} ({}, {})", playerId, player.getCurrentRoomId(), player.getX(), player.getY());
        return player;
    }

    /**
     * 玩家离开酒馆（断连或主动离开）。
     * 不做删除，保留位置信息以便下次进入时恢复。
     */
    @Transactional
    public void leaveTavern(String playerId) {
        playerRepository.findById(playerId).ifPresent(player -> {
            player.setOnline(false);
            player.setLastActiveAt(LocalDateTime.now());
            playerRepository.save(player);
            log.info("玩家 [{}] 已离开酒馆", playerId);
        });
    }

    /**
     * 更新玩家位置，带边界校验。
     */
    @Transactional
    public Optional<Player> updatePosition(String playerId, int x, int y, int roomWidth, int roomHeight) {
        return playerRepository.findById(playerId).map(player -> {
            // 边界约束：不允许走出房间边界
            player.setX(Math.max(0, Math.min(x, roomWidth)));
            player.setY(Math.max(0, Math.min(y, roomHeight)));
            player.setLastActiveAt(LocalDateTime.now());
            return playerRepository.save(player);
        });
    }

    /**
     * 获取当前所有在线玩家。
     */
    public List<Player> getOnlinePlayers() {
        return playerRepository.findByOnlineTrue();
    }

    /**
     * 获取单个玩家。
     */
    public Optional<Player> getPlayer(String playerId) {
        return playerRepository.findById(playerId);
    }
}
