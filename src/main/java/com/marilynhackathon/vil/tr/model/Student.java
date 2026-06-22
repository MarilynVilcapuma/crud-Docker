package com.marilynhackathon.vil.tr.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "students")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "dni", nullable = false, unique = true, length = 8)
    private String dni;
    private String firstName;
    private String lastName;
    private Integer promotion;
    private LocalDateTime date;
}

