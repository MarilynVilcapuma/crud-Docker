package com.marilynhackathon.vil.tr.service.impl;

import com.marilynhackathon.vil.tr.model.Student;
import com.marilynhackathon.vil.tr.repository.StudentRepository;
import com.marilynhackathon.vil.tr.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudentServiceImpl implements StudentService {

    private final StudentRepository repository;

    @Override
    public List<Student> findAll() {
        log.info("Invocar - Listando todos los estudiantes");
        return repository.findAll();
    }

    @Override
    public Student findById(Long id) {
        log.info("Invocar - Buscando estudiante con id: {}", id);
        return getOrThrow(id);
    }

    @Override
    public Student save(Student student) {
        student.setDate(LocalDateTime.now());
        Student saved = repository.save(student);
        log.info("Registrar - Estudiante registrado: dni={}, nombre={} {}", saved.getDni(), saved.getFirstName(), saved.getLastName());
        return saved;
    }

    @Override
    public Student update(Long id, Student student) {
        Student existing = getOrThrow(id);
        existing.setDni(student.getDni());
        existing.setFirstName(student.getFirstName());
        existing.setLastName(student.getLastName());
        existing.setPromotion(student.getPromotion());
        existing.setDate(LocalDateTime.now());
        Student updated = repository.save(existing);
        log.info("Actualizar - Estudiante actualizado: dni={}, nombre={} {}", updated.getDni(), updated.getFirstName(), updated.getLastName());
        return updated;
    }

    @Override
    public void delete(Long id) {
        Student existing = getOrThrow(id);
        repository.deleteById(id);
        log.info("Eliminar - Estudiante eliminado: dni={}, nombre={} {}", existing.getDni(), existing.getFirstName(), existing.getLastName());
    }

    private Student getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Estudiante no encontrado con id: " + id));
    }
}
