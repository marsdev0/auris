import os


class StorageSettings:
    ENDPOINT = os.getenv("RUSTFS_ENDPOINT", "http://localhost:9100")
    ACCESS_KEY = os.getenv("RUSTFS_ACCESS_KEY", "auris")
    SECRET_KEY = os.getenv("RUSTFS_SECRET_KEY", "auris-secret")
    BUCKET = os.getenv("RUSTFS_BUCKET", "auris-dubbing")
    REGION = os.getenv("RUSTFS_REGION", "cn-north-1")