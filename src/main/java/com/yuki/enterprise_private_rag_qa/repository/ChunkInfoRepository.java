package com.yuki.enterprise_private_rag_qa.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.yuki.enterprise_private_rag_qa.model.ChunkInfo;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
}
