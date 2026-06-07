package com.taverntales.domain.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * 世界/场景管理服务。
 *
 * 职责：加载房间定义、维护世界状态、提供场景查询。
 *
 * 为什么房间数据从 JSON 加载而非直接 SQL？
 *  房间数据（布局、家具位置、NPC 初始位置）本质是静态配置，
 *  放在 JSON 文件中方便非技术人员编辑和版本管理。
 *  后续可用管理后台替换文件加载方式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldService {

    private final RoomRepository roomRepository;
    private final ObjectMapper objectMapper;

    /**
     * 启动时从 JSON 文件加载房间定义到数据库。
     * 如果房间已存在则跳过（幂等）。
     *
     * 注意：修改 rooms.json 后需要重启应用才能生效。
     * 后续可加一个 /admin/reload 端点实现热加载。
     */
    @PostConstruct
    @Transactional
    public void initRooms() {
        try {
            InputStream input = getClass().getResourceAsStream("/data/rooms.json");
            if (input == null) {
                log.warn("未找到 rooms.json，使用默认房间");
                createDefaultRoom();
                return;
            }

            List<Room> rooms = objectMapper.readValue(input, new TypeReference<List<Room>>() {});
            for (Room room : rooms) {
                if (!roomRepository.existsById(room.getId())) {
                    roomRepository.save(room);
                    log.info("加载房间: {} ({})", room.getName(), room.getId());
                }
            }
            log.info("世界加载完成，共 {} 个房间", roomRepository.count());
        } catch (Exception e) {
            log.error("加载房间配置失败，使用默认房间", e);
            createDefaultRoom();
        }
    }

    /**
     * 兜底：JSON 文件不存在或解析失败时创建默认酒馆房间。
     */
    private void createDefaultRoom() {
        if (!roomRepository.existsById("tavern")) {
            Room tavern = Room.builder()
                    .id("tavern")
                    .name("酒馆大厅")
                    .description("一间温暖而昏暗的酒馆。壁炉里的火光跳跃着，空气中弥漫着麦酒和烤肉的气味。")
                    .width(800)
                    .height(600)
                    .defaultSpawnX(50)
                    .defaultSpawnY(300)
                    .objectsJson("[]")
                    .build();
            roomRepository.save(tavern);
            log.info("已创建默认房间: 酒馆大厅");
        }
    }

    /**
     * 获取所有房间。
     */
    public List<Room> getAllRooms() {
        return roomRepository.findAll();
    }

    /**
     * 获取单个房间。
     */
    public Optional<Room> getRoom(String roomId) {
        return roomRepository.findById(roomId);
    }
}
