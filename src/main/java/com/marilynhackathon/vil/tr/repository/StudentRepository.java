package com.marilynhackathon.vil.tr.repository;

import com.marilynhackathon.vil.tr.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
}
