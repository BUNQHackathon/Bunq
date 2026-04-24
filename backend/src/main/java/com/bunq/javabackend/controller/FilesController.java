package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.response.PresignedUrlResponseDTO;
import com.bunq.javabackend.service.FilesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FilesController {

    private final FilesService filesService;

    @GetMapping("/presigned-url")
    public PresignedUrlResponseDTO getPresignedUrl(@RequestParam("s3Uri") String s3Uri) {
        return filesService.presignKbObject(s3Uri);
    }
}
