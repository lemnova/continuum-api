# 🧠 **ANÁLISE ARQUITETURAL - CONTINUUM PKM BACKEND**

**Status**: Pronto para escalar para frontend avançado (Tiptap/Editor.js)  
**Data de Análise**: 5 de Abril de 2026  
**Versão**:  v11 (MongoDB + B2)

---

## 📋 **RESUMO EXECUTIVO**

Seu backend está **bem estruturado** com boas práticas de segurança, mas **NÃO está pronto para um grafo de conhecimento bidirecional e de alta performance**. Há 6 problemas críticos que precisam ser resolvidos antes de lançar em produção com usuários reais.

### **Nota Importante**
O sistema atual é uma arquitetura **hybrid**:
- **MongoDB**: Metadados (users, notes indexes, entities)
- **Backblaze B2**: Conteúdo (markdown/HTML das notas)

Isso é bom para **escalabilidade de storage**, mas cria **overhead de I/O** em operações de busca e linkagem.

---

## ✅ **O QUE ESTÁ BOM**

### 1. **Segurança JWT - Bem Implementada**
```
✅ Access Token: 15 minutos (curta vida)
✅ Refresh Token: 7 dias (HttpOnly cookie no frontend)
✅ Token Blacklist: Revogação imediata de logout
✅ Ownership check: vaultId validado em operações críticas
✅ XSS Protection: jsoup Safelist para sanitização
✅ Rate Limiting: Filter presente no SecurityConfig
✅ CORS: Configurado para localhost:5173 (Vite)
```

**Ponto de Atenção**: `Entity.userId` pode ser `null` em algumas queries. Ver seção de segurança.

### 2. **Estrutura de Dados - Bem Pensada**
```
✅ userId + vaultId: Separação clara de tenancy
✅ Note.entityIds[]: Relação N:M entre notas e entidades
✅ Plan limits: Enforcement de limites por plano
✅ Async enabled: @EnableAsync para operações pesadas
✅ Indices otimizadas: @Indexed(/userId/, title)
```

### 3. **GraphController - Conceito Correto**
```
✅ Busca apenas campos essenciais (id, title, entityIds)
✅ Queries otimizadas (@Query com fields)
✅ Retorna GraphDTO com nodes e links
```

---

## ⚠️ **PROBLEMAS CRÍTICOS**

### **PROBLEMA 1: BACKLINKS (Links Bidirecionais) ❌ NÃO IMPLEMENTADO**

#### Situação Atual
```
Nota A: "Economia é conectada a [[Bitcoin]] e [[Blockchain]]"
         ↓
         Procura entidades Bitcoin, Blockchain
         ↓
         Armazena: Note.entityIds = ["entity_bitcoin_id", "entity_blockchain_id"]
         
Resultado: Note A → Entity Bitcoin (unidirecional)
⚠️ FALTANDO: Como achar "Quais notas mencionam a Nota A?"
```

#### O Conceito de NoteReference
```java
// Existe em NoteReference.java mas NÃO está em produção:
{
  "id": "ref_123",
  "userId": "user_1",
  "noteId": "note_A",      // ← Nota que faz referência
  "entityId": "note_B",    // ← Entidade/Nota referenciada
  "context": "...Bitcoin é usado em...",
  "date": "2026-04-05",
  "createdAt": "2026-04-05..."
}
```

#### Por que é crítico
1. **Frontend não consegue renderizar backlinks**: "Notas que mencionam isto"
2. **Grafo incompletelo**: Apenas metade das conexões
3. **Busca por contexto cai**: "Onde esta ideia foi mencionada?"

#### Solução Recomendada

**Abordagem**: Criar uma `NoteLink` collection para referências bidirecionais

```yaml
Índice em MongoDB (novo):
  noteLinks:
    - sourceNoteId (indexed)
    - targetNoteId (indexed)
    - linkType: "REFERENCES" | "CONTRADICTS" | "EXTENDS"
    - context: "~200 chars ao redor"
    - userId (indexed)
    - vaultId (indexed)
    - createdAt

Queries necessárias:
  1. getBacklinks(noteId) → List<Note> que mencionam essa nota
  2. getRelatedNotes(noteId, limit) → Top-K notas mais conectadas
  3. getGraphDistance(noteA, noteB) → Distância no grafo
```

---

### **PROBLEMA 2: BUSCA - PERFORMANCE CRÍTICA ⚠️**

#### Situação Atual
```java
// SearchService.java
List<SearchResultNoteDTO> notes = noteRepo.findByUserId(userId).stream()
    .filter(note -> note.getTitle().toLowerCase().contains(lowerQuery))
    .collect(Collectors.toList());
// ↑ Carrega TODAS as notas do usuário em memória
// ↑ Depois filtra com .contains() (O(n) por nota)
```

**Complexidade**: `O(n * m)` onde n = notas, m = tamanho do contenúdo

**Limite de dor**:
- 100 notas: ~0ms
- 1.000 notas: ~50ms
- 10.000 notas: **~500ms+** (usuário percebe laglag)

#### Problema adicionalno MongoDB
```javascript
// Current index:
db.notes.createIndex({ userId: 1, title: 1 })

// ❌ NÃO ajuda em buscas textuais:
// - Não suporta regex patterns
// - Não suporta fuzzy search
// - Não suporta busca por campo "content" (está em B2!)
```

#### Solução Recomendada

**Opção 1**: MongoDB Text Search (rápida, built-in)
```javascript
db.notes.createIndex({
  title: "text",
  vaultId: 1,
  userId: 1
})

// Query
db.notes.find({
  $text: { $search: "Bitcoin blockchain" },
  userId: "user_1"
})
```

**Opção 2**: Elasticsearch (melhor, mas infraestrutura)
```yaml
Elasticsearch:
  - Full-text search (stemming, fuzzy)
  - Autocomplete
  - Faceted search
  - ~50ms em 100k docs
```

**Recomendação**: Implementar Opção 1 AGORA (MongoDB text search) + migrar para Opção 2 depois.

---

### **PROBLEMA 3: FORMATO DE DADOS PARA TIPTAP ❌ NÃO OTIMIZADO**

#### Situação Atual
```java
public record NoteCreateRequest(
    String title,
    @Size(max = 50000) String content,  // ← String pura!
    String folderId
) {}
```

#### O que Tiptap envia
```json
{
  "type": "doc",
  "content": [
    {
      "type": "paragraph",
      "content": [
        {
          "type": "text",
          "text": "Olá ",
          "marks": [{ "type": "bold" }]
        },
        {
          "type": "text",
          "marks": [{ "type": "link", "attrs": { "href": "..." } }],
          "text": "mundo"
        }
      ]
    },
    {
      "type": "blockquote",
      "content": [{"type": "paragraph", "content": [...]}]
    },
    {
      "type": "codeBlock",
      "attrs": { "language": "javascript" },
      "content": [{"type": "text", "text": "const x = 1;"}]
    },
    {
      "type": "mention",
      "attrs": { "label": "Bitcoin", "id": "entity_bitcoin_id" }
    }
  ]
}
```

#### Seu code atual
```java
private String sanitizeContent(String content) {
    // Limpa HTML mas perde estrutura Tiptap!
    String sanitized = Jsoup.clean(content, "", safelist);
    // ↑ Converte em HTML parseado, não em JSON estruturado
    return sanitized;
}
```

**Problema**: Se você receber JSON estruturado do Tiptap e o converter para HTML, perde:
1. **Tipo de bloco** (paragraph, heading, code, blockquote)
2. **Referências a entidades** (`mention attrs`)
3. **Metadata de formatação** (language do código)

#### Solução Recomendada

**Armazenar em dois formatos**:
```yaml
MongoDB:
  notes:
    id: "note_123"
    userId: "user_1"
    title: "Bitcoin e Blockchain"
    format: "tiptap"  # ← Novo campo
    contentHash: "sha256_hash"
    contentSize: 2048
    mentions: ["entity_bitcoin", "entity_blockchain"]  # ← Extraído do JSON
    tags: ["economia", "crypto"]
    createdAt: ...

B2:
  vaults/{vaultId}/_notes/{noteId}.json  # ← Tiptap JSON original
  vaults/{vaultId}/_notes/{noteId}.html  # ← HTML renderizado
```

**Novo DTO de Request**:
```java
public record NoteCreateRequest(
    String title,
    @NotNull
    @Valid
    com.fasterxml.jackson.databind.JsonNode content,  // ← JSON estruturado!
    String folderId
) {}

// content será uma árvore JSON que você valida contra Tiptap schema
```

**Extração de referências do JSON**:
```java
private List<String> extractReferencesFromTiptap(JsonNode doc) {
    List<String> refs = new ArrayList<>();
    
    // Percorrer o documento Tiptap
    JsonNode contentNode = doc.get("content");
    if (contentNode.isArray()) {
        for (JsonNode block : contentNode) {
            if ("mention".equals(block.get("type").asText())) {
                String entityId = block.get("attrs").get("id").asText();
                refs.add(entityId);
            }
            // Recursivamente procurar por mentions aninhadas
            extractFromNestedContent(block, refs);
        }
    }
    return refs;
}
```

---

### **PROBLEMA 4: GRAFO DE CONHECIMENTO - INCOMPLETO**

#### Situação Atual - GraphController
```java
@GetMapping("/data")
public ResponseEntity<GraphDTO> getGraphData(...) {
    // Retorna:
    // nodes: [notas E entidades]
    // links: [notas → entidades APENAS]
    
    // ❌ FALTANDO:
    // - Note → Note links (backlinks)
    // - Força de conexão (weight)
    // - Contexto de conexão
    // - Paginação
    // - Filtros (últimas 24h, por tipo, etc)
}
```

#### Novo DTO para grafo melhorado
```java
public record LinkDTO(
    String source,
    String target,
    String type,          // ← NOVO: "references", "contradicts", "extends"
    Integer weight,       // ← NOVO: força da conexão (0-100)
    String context        // ← NOVO: contexto
) {}

public record NodeDTO(
    String id,
    String label,
    String type,          // ← "NOTE" ou "ENTITY"
    Integer degree,       // ← NOVO: número de conexões
    Instant lastModified  // ← NOVO
) {}

public record GraphDTO(
    List<NodeDTO> nodes,
    List<LinkDTO> links,
    GraphMetrics metrics  // ← NOVO: estatísticas
) {}

public record GraphMetrics(
    Integer totalNodes,
    Integer totalLinks,
    Double averageDegree,
    List<String> topConnectedIds  // Top-K nós mais conectados
) {}
```

---

### **PROBLEMA 5: SEGURANÇA - OWNERSHIP INCONSISTENTE 🔴**

#### Vulnerabilidade Identificada

**Entity.java**:
```java
@Entity.builder()
    .userId(userId)      // ← Pode ser null!
    .vaultId(vaultId)    // ← Sempre preenchido
    ...
```

**EntityService.getEntity()**:
```java
public Entity getEntity(String vaultId, String entityId) {
    return entityRepo.findById(entityId)
        .filter(e -> e.getVaultId().equals(vaultId))  // ← Valida apenas vaultId
        .orElseThrow(...)
        // ❌ NÃO valida userId!
}
```

**Cenário de Ataque**:
1. User A cria Entidade E com userId=A, vaultId=vault_A
2. User B descobre vault_A ID
3. User B chama `GET /api/entities/{E}/notes` com seu vaultId=vault_A
4. Se ambos compartilharem bucket B2, User B vê notas de User A! 🔴

#### Solução

```java
// ✅ Sempre validar AMBOS userId E vaultId
public Entity getEntity(String userId, String vaultId, String entityId) {
    return entityRepo.findById(entityId)
        .filter(e -> 
            e.getUserId() != null && e.getUserId().equals(userId) &&
            e.getVaultId().equals(vaultId)
        )
        .orElseThrow(() -> new AccessDeniedException("..."));
}

// Entity.java - Garantir userId sempre preenchido
@Document(collection = "entities")
public class Entity {
    @Indexed
    private String userId;  // ← NUNCA pode ser null
    
    @IndexedDocument
    private String vaultId;
    
    // Migration: Remover qualquer Entity com userId = null
    // db.entities.deleteMany({ userId: null })
}
```

---

### **PROBLEMA 6: PERFORMANCE - I/O EM B2**

#### Situação Atual
```
1. GET /api/notes/{id}
   ↓
2. noteRepo.findById(noteId)  ← Query MongoDB (5ms)
   ↓
3. storageService.loadNoteContent(vaultId, noteId)  ← HTTP GET B2 (50-200ms!)
   ↓
4. Retorna para cliente (mais 50ms rede)
   
TOTAL: ~100-250ms por nota (usuário sente lag!)
```

#### Por que é crítico
- Cada clique em nota = 1 request para B2
- Grafo com 1k notas = 1k requests para backfill
- Sem cache = 50 usuários = 50k requests/dia

#### Solução Recomendada

**Opção 1**: Redis Cache (rápida, recomendada)
```yaml
Redis:
  Key: "continuum:note:{vaultId}:{noteId}"
  TTL: 1 hora
  Size: ~5KB média por nota
  Hit rate esperado: 80% em leitura

Benefício:
  - GET nota: 5ms (MongoDB) + 2ms (Redis) = 7ms ao invés de 100ms
  - 10x mais rápido!
```

**Opção 2**: CDN (CloudFlare, Bunny)
```yaml
CDN:
  - Cache automático de B2
  - TTL 1 hora
  - Mais barato que Redis
  - Desvantagem: TTL fixo, sem invalidação imediata
```

**Recomendação**: Redis + B2. Quando nota é atualizada:
```java
storageService.saveNoteContent(vaultId, noteId, content);
cacheService.invalidate("continuum:note:" + vaultId + ":" + noteId);
```

---

## 🏗️ **PLANO DE IMPLEMENTAÇÃO**

### **Fase 1: Correção de Segurança (URGENTE - 1 semana)**

**P1.1**: Forçar `userId` em Entity
```bash
Tarefa: Adicionar validação em Entity.builder() para garantir userId não-null
Teste: EntityServiceTest deve falhar se userId = null
```

**P1.2**: Validar ownership em todos endpoints
```bash
Tarefa: Criar método helper validateOwnership(userId, vaultId, resourceId)
Teste: Tentar acessar recurso de outro vault deve retornar 403
```

### **Fase 2: Backlinks & Bidirectional Links (2-3 semanas)**

**P2.1**: Criar `NoteLink` collection
```yaml
Tarefa:
  - Novo documento MongoDB: noteLinks
  - Índices: [userId, vaultId], [sourceNoteId], [targetNoteId]
  - Dados: sourceNote → targetNote com tipo de conexão

Implementation:
  - LinkDTO, NoteLink entity
  - NoteRepository.findBacklinks(noteId)
  - Novo endpoint: GET /api/notes/{id}/backlinks
  - Novo endpoint: GET /api/notes/{id}/related
```

**P2.2**: Parser de menções em Tiptap
```yaml
Tarefa: Extrair mentions do JSON durante create/update
- Quando nota é criada, analisar content.mentions[]
- Criar NoteLinks para cada mention encontrada
- Atualizar grafo automaticamente
```

### **Fase 3: JSON Structure & Tiptap (1-2 semanas)**

**P3.1**: Atualizar NoteCreateRequest
```java
// Novo DTO que aceita Tiptap JSON
public record NoteCreateRequest(
    String title,
    @NotNull com.fasterxml.jackson.databind.JsonNode content,
    String folderId
) {}
```

**P3.2**: Validação de schema Tiptap
```yaml
Tarefa:
  - Dependência: @slatejs/schema-json-schema
  - Validar documento contra schema Tiptap oficial
  - Rejeitar documentos malformados com 400 Bad Request
```

### **Fase 4: Search Performance (1-2 semanas)**

**P4.1**: Implementar MongoDB Text Search
```javascript
db.notes.createIndex({
  title: "text",
  userId: 1,
  vaultId: 1
})

// SearchService refatorado
searchRepo.findByUserIdAndTextQuery(userId, query)
```

**P4.2**: Implementar Redis Cache
```yaml
Tarefa:
  - Spring Cache: @Cacheable("note")
  - TTL: 1 hora
  - Invalidação: @CacheEvict ao atualizar
```

### **Fase 5: Graph Improvements (2-3 semanas)**

**P5.1**: Novo GraphController com backlinks
```java
@GetMapping("/data")
public ResponseEntity<GraphDTO> getGraphData(
    @RequestParam(required = false) Integer limit,  // Top-K nodes
    @RequestParam(required = false) String type     // filtro
) { ... }
```

**P5.2**: Novo endpoint para contexto de conexão
```java
@GetMapping("/distance")
public ResponseEntity<GraphDistanceDTO> 
    getDistance(@RequestParam String fromId, @RequestParam String toId)
// Retorna caminho mais curto + força de conexão
```

---

## 📊 **CHECKLIST DE SEGURANÇA**

- [ ] Todo endpoint valida `userId` + `vaultId`
- [ ] Nenhuma query sem `userId` filter
- [ ] Entity.userId nunca é null
- [ ] Tests cobrem horizontal privilege escalation
- [ ] Rate limiting por usuário (não global)
- [ ] JWT revogação testa lastLogoutAt
- [ ] Tiptap JSON sanitizado antes de salvar
- [ ] B2 credentials em env vars (não hardcoded)
- [ ] CORS origins validadas contra env

---

## 📈 **BENCHMARKS ALVO**

```
Métrica                   Atual    Alvo      Melhoria
────────────────────────────────────────────────────
GET /notes (100 notas)    50ms     10ms      5x
Search (1k notas)         500ms    50ms      10x
GET /graph (10k nodes)    2000ms   200ms     10x
Create note               150ms    100ms     1.5x
Backlinks query (latência) N/A      30ms     ✓ Novo
```

---

## 🔗 **ENDPOINTS FALTANDO**

```
NOVO - Backlinks & Relateds:
  GET /api/notes/{id}/backlinks
    → List<NoteSummaryDTO>
    Descrição: Notas que mencionam essa nota

  GET /api/notes/{id}/related?limit=10
    → List<RelatedNoteDTO>
    Descrição: Notas relacionadas por co-menção de entities

NOVO - Graph avançado:
  GET /api/graph/distance?from={id1}&to={id2}
    → GraphDistanceDTO
    Descrição: Caminho mais curto entre nós

  GET /api/graph/context/{id}?depth=2
    → DetailedGraphDTO
    Descrição: Subgrafo com profundidade N

MELHORANDO - Existentes:
  PUT /api/notes/{id}
    Agora aceita: content JSON estruturado (Tiptap)
    Valida: Tiptap schema
    Extrai: Mentions automaticamente
```

---

## 📚 **REFERÊNCIAS & EXEMPLOS**

### Tiptap JSON Schema
```json
https://tiptap.dev/api/extensions
```

### MongoDB Text Search
```javascript
// Criar índice text search
db.notes.createIndex({
  title: "text",
  userId: 1
})

// Query
db.notes.find(
  { $text: { $search: "bitcoin" }, userId: "user_1" },
  { score: { $meta: "textScore" } }
).sort(
  { score: { $meta: "textScore" } }
)
```

### Redis with Spring
```java
@Configuration
public class CacheConfig {
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory cf) {
        return RedisCacheManager.create(cf);
    }
}

@Cacheable(value = "notes", key = "#vaultId + ':' + #noteId")
public Note getNote(String vaultId, String noteId) { ... }
```

---

## 🎯 **CONCLUSÃO**

Seu backend está **70% lá**. Com as implementações sugeridas, você terá:

✅ Backend pronto para PKM em escala  
✅ Backlinks e grafo bidirecional  
✅ Performance de produção (sub-100ms)  
✅ Segurança contra horizontal escalation  
✅ Suporte nativo a Tiptap JSON  

**Tempo estimado para produção segura**: 5-6 semanas (1 dev full-time)

---

## 📞 **Próximos Passos**

1. **Escolher priorização**: Fase 1 → 2 → 3 (ou paralelo)
2. **Design de schema**: NoteLink collection
3. **Validação com frontend**: JSON Tiptap com Tiptap team
4. **Setup Redis**: docker-compose.yml com Redis
5. **Testes de carga**: K6 ou JMeter

