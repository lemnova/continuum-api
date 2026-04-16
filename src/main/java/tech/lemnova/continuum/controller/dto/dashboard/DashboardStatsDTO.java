package tech.lemnova.continuum.controller.dto.dashboard;

/**
 * Estatísticas rápidas: totais agregados
 */
public record DashboardStatsDTO(
    long totalNotes,        // Total de notas
    long totalEntities,     // Total de entidades não-hábito
    long totalHabits,       // Total de hábitos (entidades com type = HABIT)
    long activeHabits,      // Hábitos com atividade nos últimos 7 dias
    long totalTypes         // Número de tipos únicos de notas
) {
}
