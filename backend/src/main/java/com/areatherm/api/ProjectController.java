package com.areatherm.api;

import com.areatherm.project.Project;
import com.areatherm.project.ProjectRepository;
import com.areatherm.project.dto.CreateProjectRequest;
import com.areatherm.project.dto.ProjectResponse;
import com.areatherm.security.AppUser;
import com.areatherm.security.AppUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Projects aren't in API_SPEC.md's own endpoint list, but every other
 * resource there (locations, comfort profiles, shelter designs, ...) is
 * scoped by {@code project_id}, so a project CRUD entry point is a
 * necessary addition. No auth/user-scoping yet -- see
 * {@link CreateProjectRequest}'s {@code ownerId} placeholder -- so
 * {@link #list()} deliberately returns every project, not just the caller's.
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final AppUserRepository appUserRepository;

    public ProjectController(ProjectRepository projectRepository, AppUserRepository appUserRepository) {
        this.projectRepository = projectRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    @Operation(summary = "List all projects (no user-scoping yet)")
    public List<ProjectResponse> list() {
        return projectRepository.findAll().stream().map(ProjectResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a project by id")
    public ProjectResponse get(@PathVariable Long id) {
        return ProjectResponse.from(requireProject(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a project",
            description = "ownerId is a temporary placeholder request field until JWT auth supplies the authenticated user automatically.")
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        AppUser owner = appUserRepository.findById(request.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("AppUser " + request.ownerId() + " not found"));

        Project project = new Project();
        project.setOwner(owner);
        project.setName(request.name());
        project.setDescription(request.description());

        return ProjectResponse.from(projectRepository.save(project));
    }

    private Project requireProject(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project " + id + " not found"));
    }
}
