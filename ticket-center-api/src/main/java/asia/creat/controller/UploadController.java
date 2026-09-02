package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/upload")
public class UploadController {

    private final FileStorageService fileStorageService;

    @PostMapping("/image")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        String url = fileStorageService.uploadImage(image);
        return Result.success(url);
    }

    @DeleteMapping("/image")
    public Result deleteImage(@RequestParam("name") String filename) {
        fileStorageService.deleteImage(filename);
        return Result.success();
    }
}
