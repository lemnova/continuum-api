package tech.lemnova.continuum.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.lemnova.continuum.application.service.EntityService;
import tech.lemnova.continuum.application.service.NoteService;
import tech.lemnova.continuum.controller.dto.graph.GraphDTO;
import tech.lemnova.continuum.controller.dto.graph.LinkDTO;
import tech.lemnova.continuum.controller.dto.graph.NodeDTO;
import tech.lemnova.continuum.domain.entity.Entity;
import tech.lemnova.continuum.domain.note.Note;
import tech.lemnova.continuum.domain.note.NoteLink;
import tech.lemnova.continuum.infra.persistence.NoteLinkRepository;
import tech.lemnova.continuum.infra.security.CustomUserDetails;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GraphController - Construir o grafo de conhecimento usando dados otimizados.
 * 
 * Usa noteLinkRepository para trazer informações semânticas dos links
 * (tipo de relação, contexto, etc) em vez de apenas listas de entityIds.
 * 
 * Performance:
 * - Usa queries otimizadas que trazem apenas campos necessários
 * - Índices compostos em NoteLink para busca rápida
 * - Em memória: constrói nós e links de forma eficiente
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final NoteService noteService;
    private final EntityService entityService;
    private final NoteLinkRepository noteLinkRepo;

    public GraphController(NoteService noteService, EntityService entityService, NoteLinkRepository noteLinkRepo) {
        this.noteService = noteService;
        this.entityService = entityService;
        this.noteLinkRepo = noteLinkRepo;
    }

    /**
     * Retorna o grafo de conhecimento completo do usuário.
     * 
     * Estrutura:
     * - Nodes: Notas (type="NOTE") + Entidades (type="ENTITY")
     * - Links: Conexões entre notas e entidades (com tipo semântico)
     * 
     * Usecases:
     * - Visualizar grafo no frontend (D3.js, Cytoscape, etc)
     * - Análise de conexões
     * - Recomendações baseadas em grafo
     */
    @GetMapping("/data")
    public ResponseEntity<GraphDTO> getGraphData(@AuthenticationPrincipal CustomUserDetails user) {
        String userId = user.getUserId();
        String vaultId = user.getVaultId();

        // Buscar apenas os dados necessários para o grafo
        List<Note> notes = noteService.listByUserForGraph();
        List<Entity> entities = entityService.listByUserForGraph(userId);

        // Criar nós das notas
        List<NodeDTO> nodes = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        
        for (Note note : notes) {
            nodes.add(new NodeDTO(note.getId(), note.getTitle(), "NOTE"));
            nodeIds.add(note.getId());
        }

        // Criar nós das entidades
        for (Entity entity : entities) {
            nodes.add(new NodeDTO(entity.getId(), entity.getTitle(), "ENTITY"));
            nodeIds.add(entity.getId());
        }

        // Criar links usando NoteLink (com informações semânticas)
        List<LinkDTO> links = new ArrayList<>();
        
        // Buscar todos os NoteLinks do usuário
        List<NoteLink> noteLinks = getAllUserNoteLinks(userId, vaultId);
        
        // Converter NoteLinks em LinkDTOs
        for (NoteLink noteLink : noteLinks) {
            // Verificar se ambos os nós existem no grafo
            if (nodeIds.contains(noteLink.getSourceNoteId()) && nodeIds.contains(noteLink.getTargetNoteId())) {
                LinkDTO link = new LinkDTO(
                    noteLink.getSourceNoteId(),
                    noteLink.getTargetNoteId(),
                    noteLink.getLinkType(),
                    noteLink.getContext(),
                    "NOTE", // source é sempre nota (por enquanto)
                    "ENTITY" // target pode ser entidade ou nota
                );
                links.add(link);
            }
        }
        
        // Adicionar links do legacy (entityIds em notas) para compatibilidade
        for (Note note : notes) {
            if (note.getEntityIds() != null && !note.getEntityIds().isEmpty()) {
                for (String entityId : note.getEntityIds()) {
                    if (nodeIds.contains(entityId)) {
                        // Verificar se já existe um NoteLink para esta conexão
                        boolean linkExists = noteLinks.stream()
                                .anyMatch(nl -> nl.getSourceNoteId().equals(note.getId()) 
                                        && nl.getTargetNoteId().equals(entityId));
                        
                        if (!linkExists) {
                            links.add(new LinkDTO(note.getId(), entityId, "NOTE", "ENTITY"));
                        }
                    }
                }
            }
        }

        return ResponseEntity.ok(new GraphDTO(nodes, links));
    }
    
    /**
     * Busca todos os NoteLinks do usuário.
     * Usa índices compostos para otimização.
     * 
     * Nota: Para grandes datasets (>10k links), considerar paginação
     */
    private List<NoteLink> getAllUserNoteLinks(String userId, String vaultId) {
        try {
            return noteLinkRepo.findByUserIdAndVaultId(userId, vaultId);
        } catch (Exception e) {
            // Log mas não falha
            return new ArrayList<>();
        }
    }
}
