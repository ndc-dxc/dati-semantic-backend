package it.gov.innovazione.ndc.alerter.data;

import it.gov.innovazione.ndc.alerter.dto.UserDto;
import it.gov.innovazione.ndc.alerter.entities.User;
import it.gov.innovazione.ndc.eventhandler.NdcEventPublisher;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService extends EntityService<User, UserDto> {
    @Getter(AccessLevel.PROTECTED)
    private final NdcEventPublisher eventPublisher;
    @Getter(AccessLevel.PROTECTED)
    private final UserRepository repository;
    @Getter(AccessLevel.PROTECTED)
    private final UserMapper entityMapper;
    @Getter(AccessLevel.PROTECTED)
    private final String entityName = "User";
    @Getter(AccessLevel.PROTECTED)
    private final Sort defaultSorting = Sort.by("name").ascending();

    @Override
    protected void assertEntityDoesNotExists(UserDto dto) {
        if (repository.existsByNameAndSurnameAndEmail(
                dto.getName(), dto.getSurname(), dto.getEmail())) {
            throw new ConflictingOperationException("An user with the same name/surname/email already exists: " + dto.getName() + "/" + dto.getSurname() + "/" + dto.getEmail());
        }
    }
}
