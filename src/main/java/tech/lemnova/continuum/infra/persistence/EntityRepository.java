package tech.lemnova.continuum.infra.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import tech.lemnova.continuum.domain.entity.Entity;
import tech.lemnova.continuum.domain.entity.EntityType;

import java.util.List;
import java.util.Optional;

/**
 * Repository para entidades com suporte a Text Search otimizado.
 * 
 * Índices de texto:
 * - { "title": "text", "userId": 1 } para busca otimizada
 */
@Repository
public interface EntityRepository extends MongoRepository<Entity, String> {
    List<Entity> findByUserId(String userId);
    long countByUserId(String userId);
    void deleteByUserId(String userId);
    List<Entity> findByVaultId(String vaultId);
    List<Entity> findByVaultIdAndType(String vaultId, EntityType type);
    List<Entity> findByIdIn(List<String> ids);

    /**
     * Busca apenas os campos necessários para construir o grafo (id, title).
     * Evita carregar campos desnecessários, economizando memória e banda.
     *
     * @param userId ID do usuário
     * @return Lista de entidades com apenas os campos essenciais para o grafo
     */
    @Query(value = "{ 'userId': ?0 }", fields = "{ 'id': 1, 'title': 1 }")
    List<Entity> findGraphDataByUserId(String userId);
    
    /**
     * Busca entidades por texto usando MongoDB Text Search.
     * MUCH mais rápido do que Java Streams filter para datasets grandes.
     * 
     * Usa índice: { "title": "text", "userId": 1 }
     * 
     * @param userId ID do usuário (filtro de segurança)
     * @param query Texto para procurar (suporta múltiplas palavras e operadores)
     * @return Lista de entidades que correspondem à query
     */
    @Query(value = """
        {
            $text: { $search: ?1 },
            userId: ?0
        }
        """, fields = "{ score: { $meta: 'textScore' } }")
    List<Entity> findByTextSearch(String userId, String query);
}

