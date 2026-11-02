offline-playlist.mp4 是 FFmpeg 生成的测试图案，不包含第三方影视内容。
生成命令：
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=10 -t 30 -c:v libx264 -preset ultrafast -crf 35 -pix_fmt yuv420p -an offline-playlist.mp4

仅供 androidTest 使用，不打入应用安装包。
