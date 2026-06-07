package com.taverntales.domain.world;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Room 实体的 JPA Repository。
 * 当前使用 H2，切换到 PostgreSQL 时无需修改此接口。
 */
@Repository
public interface RoomRepository extends JpaRepository<Room, String> {
}
