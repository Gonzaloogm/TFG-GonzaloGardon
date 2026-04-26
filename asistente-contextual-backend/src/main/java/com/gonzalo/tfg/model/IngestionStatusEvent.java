package com.gonzalo.tfg.model;

public record IngestionStatusEvent(
    String fileName,
    int phase,
    String message,
    String status // "processing", "completed", "error"
) {}
