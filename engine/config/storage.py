import os


class StorageSettings:
    ENDPOINT = os.getenv("RUSTFS_ENDPOINT", "")
    ACCESS_KEY = os.getenv("RUSTFS_ACCESS_KEY", "")
    SECRET_KEY = os.getenv("RUSTFS_SECRET_KEY", "")
    BUCKET = os.getenv("RUSTFS_BUCKET", "")
    REGION = os.getenv("RUSTFS_REGION", "")